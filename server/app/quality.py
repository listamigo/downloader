"""Núcleo puro de la escalera de calidades.

No importa yt-dlp, ni FastAPI, ni toca la red: solo datos y reglas. Por eso se
puede probar con `python -m unittest` sin instalar nada (r1: toda lógica nueva
con tests). El adaptador de yt-dlp traduce los formatos crudos a `VideoCandidate`
y `AudioCandidate` y pide aquí la escalera y el selector de descarga.

La regla de oro (ADR-022): la escalera refleja los formatos reales que el
servidor obtiene. No se recorta ni se asume ningún techo.
"""

from __future__ import annotations

from dataclasses import dataclass

BEST_ID = "best"
BEST_LABEL = "Mejor"

_VIDEO_PREFIX = "v"
_AUDIO_PREFIX = "a"

# A partir de aquí la etiqueta muestra los fps ("1080p60" frente a "1080p").
HIGH_FPS = 60
MAX_AUDIO_OPTIONS = 4

# Preferencia de códec de vídeo para el MP4. H.264 (avc1) es el que reproducen
# todos los Android sin excepción; VP9 y AV1 van después.
_VIDEO_CODEC_PRIORITY = ("avc1", "vp9", "vp09", "av01")

# Un audio m4a (AAC) se copia dentro del MP4 sin recodificar y es el que la app
# espera con extensión .m4a. Solo se ofrecen audios m4a en la escalera.
_M4A_CODECS = ("mp4a", "aac")


@dataclass(frozen=True)
class VideoCandidate:
    """Formato con pista de vídeo, ya despojado de la forma cruda de yt-dlp."""

    height: int
    fps: int
    codec: str
    filesize: int | None = None


@dataclass(frozen=True)
class AudioCandidate:
    """Formato de solo audio (m4a) con su bitrate medio en kbps."""

    abr: int
    codec: str
    filesize: int | None = None


@dataclass(frozen=True)
class Quality:
    """Entrada de la escalera, con la forma exacta que consume la app."""

    id: str
    label: str
    format: str
    height: int | None
    fps: int | None
    video_codec: str | None
    audio_codec: str | None
    file_size: int | None
    is_audio_only: bool


@dataclass(frozen=True)
class VideoSpec:
    height: int
    fps: int


@dataclass(frozen=True)
class AudioSpec:
    abr: int


def video_quality_id(height: int, fps: int) -> str:
    return f"{_VIDEO_PREFIX}:{height}:{max(0, fps)}"


def audio_quality_id(abr: int) -> str:
    return f"{_AUDIO_PREFIX}:{abr}"


def label_for(height: int, fps: int) -> str:
    return f"{height}p{fps}" if fps >= HIGH_FPS else f"{height}p"


def _codec_rank(codec: str) -> int:
    low = (codec or "").lower()
    for rank, prefix in enumerate(_VIDEO_CODEC_PRIORITY):
        if low.startswith(prefix):
            return rank
    return len(_VIDEO_CODEC_PRIORITY)


def _is_m4a(codec: str) -> bool:
    low = (codec or "").lower()
    return any(low.startswith(prefix) for prefix in _M4A_CODECS)


def _better_video(candidate: VideoCandidate, current: VideoCandidate) -> bool:
    rank, current_rank = _codec_rank(candidate.codec), _codec_rank(current.codec)
    if rank != current_rank:
        return rank < current_rank
    if candidate.filesize is not None and current.filesize is not None:
        return candidate.filesize < current.filesize
    # El que conoce su tamaño es una opción más informativa que el que no.
    return candidate.filesize is not None and current.filesize is None


def _better_audio(candidate: AudioCandidate, current: AudioCandidate) -> bool:
    if _is_m4a(candidate.codec) != _is_m4a(current.codec):
        return _is_m4a(candidate.codec)
    if candidate.filesize is not None and current.filesize is not None:
        return candidate.filesize < current.filesize
    return candidate.filesize is not None and current.filesize is None


def build_ladder(
    videos: list[VideoCandidate],
    audios: list[AudioCandidate],
) -> list[Quality]:
    """Escalera ordenada de mayor a menor: 'Mejor', vídeo y luego audio."""
    best_by_shape: dict[tuple[int, int], VideoCandidate] = {}
    for video in videos:
        if video.height <= 0:
            continue
        key = (video.height, video.fps)
        current = best_by_shape.get(key)
        if current is None or _better_video(video, current):
            best_by_shape[key] = video

    ordered = sorted(
        best_by_shape.values(),
        key=lambda v: (v.height, v.fps),
        reverse=True,
    )

    ladder: list[Quality] = []
    if ordered:
        top = ordered[0]
        ladder.append(
            Quality(
                id=BEST_ID,
                label=BEST_LABEL,
                format="mp4",
                height=None,
                fps=None,
                video_codec=top.codec,
                audio_codec=None,
                file_size=None,
                is_audio_only=False,
            )
        )
    for video in ordered:
        ladder.append(
            Quality(
                id=video_quality_id(video.height, video.fps),
                label=label_for(video.height, video.fps),
                format="mp4",
                height=video.height,
                fps=video.fps or None,
                video_codec=video.codec,
                audio_codec=None,
                file_size=video.filesize,
                is_audio_only=False,
            )
        )
    ladder.extend(_audio_ladder(audios))
    return ladder


def _audio_ladder(audios: list[AudioCandidate]) -> list[Quality]:
    best_by_abr: dict[int, AudioCandidate] = {}
    for audio in audios:
        if audio.abr <= 0:
            continue
        current = best_by_abr.get(audio.abr)
        if current is None or _better_audio(audio, current):
            best_by_abr[audio.abr] = audio

    ordered = sorted(best_by_abr.values(), key=lambda a: a.abr, reverse=True)
    return [
        Quality(
            id=audio_quality_id(audio.abr),
            label=f"{audio.abr} kbps",
            format="m4a",
            height=None,
            fps=None,
            video_codec=None,
            audio_codec=audio.codec,
            file_size=audio.filesize,
            is_audio_only=True,
        )
        for audio in ordered[:MAX_AUDIO_OPTIONS]
    ]


def parse_quality_id(quality_id: str):
    """Devuelve `("best", None)`, `("video", VideoSpec)` o `("audio", AudioSpec)`.

    Lanza `ValueError` si el id no encaja con ninguna forma conocida: un id
    corrupto es un error del cliente, no algo que se deba adivinar.
    """
    if quality_id == BEST_ID:
        return ("best", None)
    parts = quality_id.split(":")
    if len(parts) == 3 and parts[0] == _VIDEO_PREFIX:
        return ("video", VideoSpec(int(parts[1]), int(parts[2])))
    if len(parts) == 2 and parts[0] == _AUDIO_PREFIX:
        return ("audio", AudioSpec(int(parts[1])))
    raise ValueError(f"id de calidad desconocido: {quality_id!r}")


def video_selector(spec: VideoSpec | None) -> str:
    """Selector de yt-dlp para un vídeo muxeado a MP4.

    Prefiere H.264 (`avc1`) dentro de MP4: es el códec que reproducen todos los
    Android, incluidos los antiguos. Solo si no existe cae a otros códecs.
    """
    if spec is None:
        return (
            "bv*[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/"
            "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
        )
    height = f"[height={spec.height}]"
    fps = f"[fps<={spec.fps}]" if spec.fps > 0 else ""
    return (
        f"bv*{height}{fps}[vcodec^=avc1][ext=mp4]+ba[ext=m4a]/"
        f"bv*{height}{fps}[ext=mp4]+ba[ext=m4a]/"
        f"bv*{height}{fps}+ba/b"
    )


def audio_selector(spec: AudioSpec | None) -> str:
    """Selector de yt-dlp para audio m4a (sin recodificar si es posible)."""
    if spec is None:
        return "ba[ext=m4a]/ba"
    abr = f"[abr<={spec.abr}]"
    return f"ba{abr}[ext=m4a]/ba{abr}/ba[ext=m4a]/ba"


def selector_for(quality_id: str, kind: str) -> str:
    """Selector de descarga para un id de calidad y un tipo (`video`/`audio`).

    Valida que el id y el tipo concuerden: pedir vídeo con un id de audio (o al
    revés) es un error explícito, no una descarga sorpresa.
    """
    if kind not in ("video", "audio"):
        raise ValueError(f"tipo desconocido: {kind!r}")
    tag, spec = parse_quality_id(quality_id)
    if kind == "audio":
        if tag == "video":
            raise ValueError(f"se pidió audio con un id de vídeo: {quality_id!r}")
        return audio_selector(spec if tag == "audio" else None)
    if tag == "audio":
        raise ValueError(f"se pidió vídeo con un id de audio: {quality_id!r}")
    return video_selector(spec if tag == "video" else None)
