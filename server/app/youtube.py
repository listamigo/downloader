"""Adaptador de yt-dlp (Python API).

Todo lo específico de yt-dlp vive aquí. La decisión de *qué* bajar (escalera y
selector) está en `quality.py`, que es puro y testeable sin red.
"""

from __future__ import annotations

import logging
import re
import time
import urllib.request
from pathlib import Path

from yt_dlp import YoutubeDL
from yt_dlp.networking.impersonate import ImpersonateTarget
from yt_dlp.utils import DownloadError
from yt_dlp.version import __version__ as YTDLP_VERSION

from . import quality as quality_mod
from .config import Settings
from .models import QualityDTO, VideoDTO

logger = logging.getLogger("adonwloader.youtube")

WATCH_URL = "https://www.youtube.com/watch?v={}"

# IDs de YouTube son [A-Za-z0-9_-]; se valida antes de interpolar en la URL.
VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,20}$")


class YouTubeError(RuntimeError):
    """Fallo al hablar con YouTube; lleva el mensaje original de yt-dlp."""


class YouTube:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        # Mutable en caliente: `POST /api/cookies` lo actualiza sin reiniciar.
        self.cookies_file: Path | None = settings.cookies_file
        # Impersonación ensayada por llamada: curl-cffi puede fallar para un
        # objetivo concreto (target no soportado en la versión instalada), y
        # ahi hay que probar el siguiente valor en vez de morir.
        self._impersonate_plan: list[str | None] = _impersonate_plan(settings)

    # --- construcción de opciones -------------------------------------------------

    def _base_opts(self, clients: tuple[str, ...]) -> dict:
        extractor_args: dict[str, dict[str, list[str]]] = {
            "youtube": {"player_client": list(clients)}
        }
        if self._settings.po_token:
            extractor_args["youtube"]["po_token"] = [self._settings.po_token]
        # PO token por proveedor (yt-dlp >= 2025.05.22). Los args viejo
        # `youtube:getpot_bgutil_*` están deprecados y el plugin 1.3.2 los
        # ignora (verificado con yt-dlp 2026.08.19): el base_url/hook debe ir
        # en el extractor-arg del PROVEEDOR (youtubepot-bgutilhttp/script).
        if self._settings.pot_script:
            extractor_args["youtubepot-bgutilscript"] = {
                "server_home": [self._settings.pot_script]
            }
        if self._settings.pot_baseurl:
            extractor_args["youtubepot-bgutilhttp"] = {
                "base_url": [self._settings.pot_baseurl]
            }

        opts: dict = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "noplaylist": True,
            "socket_timeout": self._settings.socket_timeout,
            "retries": 2,
            "extractor_args": extractor_args,
        }
        if self._settings.proxy:
            opts["proxy"] = self._settings.proxy
        if self.cookies_file and self.cookies_file.exists():
            opts["cookiefile"] = str(self.cookies_file)
        return opts

    # --- lectura ------------------------------------------------------------------

    def health(self) -> dict:
        return {
            "ytDlp": YTDLP_VERSION,
            "cookies": bool(self.cookies_file and self.cookies_file.exists()),
            "proxy": bool(self._settings.proxy),
            "poToken": bool(self._settings.po_token),
            "potScript": bool(self._settings.pot_script),
            "potBaseurl": bool(self._settings.pot_baseurl),
            "impersonate": list(self._settings.impersonate),
        }

    def diagnose(self, video_id: str, clients: tuple[str, ...] | None = None) -> dict:
        """Telemetría para depurar el bot-check sin volar a ciegas.

        Devuelve: estado del proveedor PO (ping), la configuración efectiva, el
        resultado de una extracción **verbose** (sin descargar) y las últimas
        líneas de log de yt-dlp (redactadas), más señales calculadas sobre ellas
        para saber si el plugin de PO se invocó y si pidió un token.
        """
        logs: list[str] = []

        class _Capture:
            def debug(self, msg) -> None:  # yt-dlp manda debug y también info aquí
                logs.append(f"[debug] {msg}")

            def info(self, msg) -> None:
                logs.append(f"[info] {msg}")

            def warning(self, msg) -> None:
                logs.append(f"[warn] {msg}")

            def error(self, msg) -> None:
                logs.append(f"[error] {msg}")

        provider = _probe_provider(self._settings.pot_baseurl)
        opts = self._base_opts(clients or self._settings.video_clients)
        opts.update(
            {
                "quiet": False,
                "verbose": True,
                "logger": _Capture(),
                "skip_download": True,
            }
        )

        result: dict = {
            "videoId": video_id,
            "settings": self.health(),
            "provider": provider,
        }
        try:
            info = self._run(opts, WATCH_URL.format(video_id))
            result["status"] = "SUCCESS"
            result["title"] = info.get("title")
            result["formats"] = len(info.get("formats") or [])
            heights = sorted(
                {int(f["height"]) for f in (info.get("formats") or []) if f.get("height")}
            )
            result["heights"] = heights
        except YouTubeError as exc:
            result["status"] = "FAILED"
            result["error"] = str(exc)

        joined = "\n".join(logs)
        lower = joined.lower()
        result["signals"] = {
            "providerReachable": provider.get("reachable", False),
            "pluginMentioned": "bgutil" in lower,
            "potProviderArgUsed": "youtubepot-bgutilhttp" in lower
            or "youtubepot-bgutilscript" in lower,
            "potTokenMentioned": "po_token" in lower or "po token" in lower,
            "getPotRequested": "get_pot" in lower or "/ping" in lower,
        }
        result["logs"] = [_redact(line) for line in logs[-80:]]
        return result

    def search(self, query: str, page: int, page_size: int) -> tuple[list[VideoDTO], bool]:
        offset = max(0, page) * page_size
        end = offset + page_size
        opts = self._base_opts(self._settings.search_clients)
        opts["extract_flat"] = True
        opts["playlistend"] = end
        info = self._run(opts, f"ytsearch{end}:{query}")
        entries = [e for e in (info.get("entries") or []) if e]
        window = entries[offset:end]
        videos = [self._to_video(entry) for entry in window]
        return videos, len(window) == page_size

    def video_info(self, video_id: str) -> VideoDTO:
        info = self._run(self._base_opts(self._settings.video_clients), WATCH_URL.format(video_id))
        return self._to_video(info)

    def qualities(self, video_id: str) -> list[QualityDTO]:
        info = self._run(self._base_opts(self._settings.video_clients), WATCH_URL.format(video_id))
        videos, audios = _candidates(info.get("formats") or [])
        ladder = quality_mod.build_ladder(videos, audios)
        return [
            QualityDTO(
                id=entry.id,
                label=entry.label,
                format=entry.format,
                height=entry.height,
                fps=entry.fps,
                videoCodec=entry.video_codec,
                audioCodec=entry.audio_codec,
                fileSize=entry.file_size,
                isAudioOnly=entry.is_audio_only,
            )
            for entry in ladder
        ]

    # --- descarga -----------------------------------------------------------------

    def download(self, video_id: str, quality_id: str, kind: str, work_dir: Path) -> Path:
        """Baja y muxea a un único fichero (MP4 o M4A). Devuelve su ruta."""
        selector = quality_mod.selector_for(quality_id, kind)
        token = f"{video_id}-{abs(hash((video_id, quality_id, kind))) & 0xFFFFFFFF:x}"
        opts = self._base_opts(self._settings.video_clients)
        opts.update(
            skip_download=False,
            format=selector,
            outtmpl=str(work_dir / f"{token}.%(ext)s"),
            merge_output_format="mp4",
            overwrites=True,
            postprocessor_hooks=[],
        )
        if kind == "audio":
            # El selector ya pide m4a; si aun así cayó a otro contenedor, se
            # extrae a m4a para no servir bytes de webm con nombre .m4a.
            opts["postprocessors"] = [
                {"key": "FFmpegExtractAudio", "preferredcodec": "m4a", "preferredquality": "0"}
            ]

        self._run(opts, WATCH_URL.format(video_id))

        produced = self._final_file(work_dir, token, "m4a" if kind == "audio" else "mp4")
        if produced is None:
            raise YouTubeError(f"yt-dlp no dejó un fichero para {video_id} ({quality_id}/{kind})")
        return produced

    # --- interno ------------------------------------------------------------------

    def _run(self, opts: dict, target: str) -> dict:
        #curl-cffi impersona un navegador real: reduces el reto bot sin tocar nada mas.
        last_exc: Exception | None = None
        for impersonate in self._impersonate_plan:
            attempt = dict(opts)
            if impersonate:
                attempt["impersonate"] = impersonate
            try:
                return self._extract(attempt, target)
            except YouTubeError as exc:
                last_exc = exc
                # Solo un fallo de red/bot justifica reintentar con otra
                # impersonacion; otros errores fallo igual con cualquier plan.
                if not _is_retriable_bot_error(exc):
                    raise
        assert last_exc is not None
        raise last_exc

    def _extract(self, opts: dict, target: str) -> dict:
        # El target de impersonación debe ser un ImpersonateTarget: un string
        # plano ("chrome") atraviesa `is_supported_target` y muere en un
        # `assert isinstance(...)` dentro de yt-dlp (verificado contra 2026.08.19).
        if isinstance(opts.get("impersonate"), str):
            opts = dict(opts)
            opts["impersonate"] = ImpersonateTarget.from_str(opts["impersonate"])
        try:
            with YoutubeDL(opts) as ydl:
                info = ydl.extract_info(target, download=not opts.get("skip_download", True))
        except DownloadError as exc:
            raise YouTubeError(str(exc)) from exc
        except Exception as exc:
            # yt-dlp puede fallar antes de envolver su error (p. ej. curl-cffi
            # no soporta el target 'chrome' en una versión concreta): ese fallo
            # llega como excepción cruda y antes mataba el endpoint con un 500
            # sin detalle. Se envuelve aquí para que /api/qualities (y el resto)
            # sigan el camino normal: YouTubeError -> plan de reintento -> 502
            # con el mensaje real en vez de un 500 opaco.
            logger.warning("yt-dlp fallo con %r (target=%s)", exc, target)
            raise YouTubeError(f"{type(exc).__name__}: {exc}") from exc
        if not info:
            raise YouTubeError(f"yt-dlp no devolvió información para {target}")
        return info

    @staticmethod
    def _final_file(work_dir: Path, token: str, expected_ext: str) -> Path | None:
        produced = list(work_dir.glob(f"{token}.*"))
        if not produced:
            return None
        for candidate in produced:
            if candidate.suffix.lstrip(".") == expected_ext:
                return candidate
        return max(produced, key=lambda p: p.stat().st_size)

    @staticmethod
    def _to_video(info: dict) -> VideoDTO:
        video_id = str(info.get("id") or "")
        thumbs = info.get("thumbnails") or []
        best_thumb = max(
            thumbs,
            key=lambda t: (t.get("width") or 0) * (t.get("height") or 0),
            default=None,
        )
        thumb_url = (best_thumb or {}).get("url") or f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"
        view_count = info.get("view_count")
        return VideoDTO(
            videoId=video_id,
            title=str(info.get("title") or ""),
            channelName=str(info.get("uploader") or info.get("channel") or ""),
            duration=_format_duration(info.get("duration")),
            viewCount=str(int(view_count)) if isinstance(view_count, (int, float)) else "",
            uploadDate=str(info.get("upload_date") or ""),
            thumbnailUrl=str(thumb_url),
            isLive=bool(info.get("is_live") or False),
        )


def _impersonate_plan(settings: Settings) -> list[str | None]:
    """Plan de impersonación: valores configurados + un intento final desnudo.

    El último intento sin `impersonate` garantiza que si curl-cffi no puede con
    algo, el comportamiento anterior (sin impersonar) siga disponible como
    respaldo en vez de morir por el plan nuevo.
    """
    plan: list[str | None] = [value for value in settings.impersonate if value]
    plan.append(None)
    return plan


def _probe_provider(base_url: str | None) -> dict:
    """Ping al proveedor de PO tokens para saber si responde (y si tardó)."""
    if not base_url:
        return {"configured": False, "reachable": False}
    info: dict = {"configured": True, "url": base_url}
    try:
        started = time.time()
        with urllib.request.urlopen(base_url.rstrip("/") + "/ping", timeout=5) as resp:
            body = resp.read(200).decode("utf-8", "replace")
        info.update(
            reachable=True,
            pingMs=int((time.time() - started) * 1000),
            pingBody=body,
        )
    except Exception as exc:  # noqa: BLE001 - se reporta, no se oculta
        info.update(reachable=False, error=f"{type(exc).__name__}: {exc}")
    return info


# Cookies/tokens que podrían filtrarse en un log verbose de yt-dlp.
_SECRET_RE = re.compile(
    r"(SID|SAPISID|APISID|HSID|SSID|ST-[A-Za-z0-9_-]+|__Secure-[A-Za-z0-9_-]+)=[^;\s\"']+",
    re.IGNORECASE,
)
_LONG_RE = re.compile(r"[A-Za-z0-9_\-]{40,}")


def _redact(line: str) -> str:
    """Enmascara valores sensibles en una línea de log antes de exponerla."""
    line = _SECRET_RE.sub(lambda m: m.group(1) + "=***", line)
    return _LONG_RE.sub("***", line)


def _is_retriable_bot_error(exc: YouTubeError) -> bool:
    message = str(exc).lower()
    return (
        "sign in to confirm" in message
        or "not a bot" in message
        or "unable to download webpage" in message
        or "http error 4" in message
    )


def _candidates(formats: list[dict]) -> tuple[list[quality_mod.VideoCandidate], list[quality_mod.AudioCandidate]]:
    videos: list[quality_mod.VideoCandidate] = []
    audios: list[quality_mod.AudioCandidate] = []
    for fmt in formats:
        vcodec = fmt.get("vcodec") or "none"
        acodec = fmt.get("acodec") or "none"
        height = _int(fmt.get("height"))
        if vcodec != "none" and height > 0:
            videos.append(
                quality_mod.VideoCandidate(
                    height=height,
                    fps=_int(fmt.get("fps")),
                    codec=vcodec,
                    filesize=_filesize(fmt),
                )
            )
        if acodec != "none" and vcodec == "none" and fmt.get("ext") == "m4a":
            abr = _int(fmt.get("abr"))
            if abr > 0:
                audios.append(
                    quality_mod.AudioCandidate(
                        abr=abr,
                        codec=acodec,
                        filesize=_filesize(fmt),
                    )
                )
    return videos, audios


def _int(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _filesize(fmt: dict) -> int | None:
    for key in ("filesize", "filesize_approx"):
        value = fmt.get(key)
        if isinstance(value, (int, float)) and value > 0:
            return int(value)
    return None


def _format_duration(seconds) -> str:
    total = _int(seconds)
    if total <= 0:
        return ""
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"
