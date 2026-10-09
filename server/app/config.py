"""Configuración por entorno. Todo lo sensible es opcional: sin nada
configurado el servidor arranca y funciona (degradado, como cualquier proceso
sin proxy ni cookies), pero nunca falla por una variable ausente.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

# Clientes de InnerTube. Medido el 2026-10-09: sin PO token, *ningún* cliente
# pasa de 360p (itag 18), ni con cookies. Con PO token, `default` expone la
# escalera completa hasta 4K (`mweb` falla con PO token: "enumerate_adapters").
# Por eso `default` va primero y el PO token es la palanca real de calidad.
DEFAULT_VIDEO_CLIENTS = ("default", "web_safari", "android", "web")
DEFAULT_SEARCH_CLIENTS = ("default", "web_safari", "android", "web")


@dataclass(frozen=True)
class Settings:
    cache_dir: Path
    cache_ttl_seconds: int
    proxy: str | None
    cookies_file: Path | None
    po_token: str | None
    pot_script: str | None
    pot_baseurl: str | None
    video_clients: tuple[str, ...]
    search_clients: tuple[str, ...]
    socket_timeout: int

    @staticmethod
    def from_env(env: dict[str, str] | None = None) -> "Settings":
        env = env if env is not None else dict(os.environ)

        cache_dir = Path(env.get("MEDIA_CACHE_DIR", "/tmp/adonwloader-media"))
        proxy = env.get("YTDLP_PROXY") or env.get("RESIDENTIAL_PROXY") or None

        cookies_env = env.get("YTDLP_COOKIES") or env.get("COOKIES_FILE")
        cookies_file = Path(cookies_env) if cookies_env else None

        po_token = env.get("YT_PO_TOKEN") or None

        video_clients = _clients(env.get("VIDEO_CLIENTS"), DEFAULT_VIDEO_CLIENTS)
        search_clients = _clients(env.get("SEARCH_CLIENTS"), DEFAULT_SEARCH_CLIENTS)

        return Settings(
            cache_dir=cache_dir,
            cache_ttl_seconds=int(env.get("MEDIA_CACHE_TTL", "21600")),
            proxy=proxy,
            cookies_file=cookies_file if (cookies_file and cookies_file.exists()) else None,
            po_token=po_token,
            pot_script=env.get("YTDLP_POT_SCRIPT") or None,
            pot_baseurl=env.get("YTDLP_POT_BASEURL") or None,
            video_clients=video_clients,
            search_clients=search_clients,
            socket_timeout=int(env.get("YTDLP_SOCKET_TIMEOUT", "20")),
        )


def _clients(raw: str | None, default: tuple[str, ...]) -> tuple[str, ...]:
    if not raw:
        return default
    parsed = tuple(part.strip() for part in raw.split(",") if part.strip())
    return parsed or default
