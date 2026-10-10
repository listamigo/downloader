"""API del backend remoto (plan B, ADR-022).

FastAPI + yt-dlp. Entrega metadatos y **sirve los bytes** del medio ya muxeado
con soporte `Range`, para que el móvil baje un único archivo.
"""

from __future__ import annotations

import hashlib
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, RedirectResponse

from . import quality as quality_mod
from .cache import MediaCache
from .config import Settings
from .models import (
    CookieResultDTO,
    CookiesDTO,
    HealthDTO,
    QualityDTO,
    SearchDTO,
    VideoDTO,
)
from .naming import safe_filename
from .youtube import VIDEO_ID_RE, YouTube, YouTubeError

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("adonwloader")

VERSION = "0.1.0"

settings = Settings.from_env()
youtube = YouTube(settings)
cache = MediaCache(settings.cache_dir, settings.cache_ttl_seconds)


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.cache_dir.mkdir(parents=True, exist_ok=True)
    removed = cache.purge_expired()
    logger.info(
        "adonwloader %s | cache=%s ttl=%ss purge=%s proxy=%s cookies=%s",
        VERSION,
        settings.cache_dir,
        settings.cache_ttl_seconds,
        removed,
        bool(settings.proxy),
        bool(settings.cookies_file),
    )
    yield


app = FastAPI(title="adonwloader-backend", version=VERSION, lifespan=lifespan)


@app.get("/", include_in_schema=False)
async def root() -> RedirectResponse:
    return RedirectResponse(url="/docs")


@app.get("/api/health", response_model=HealthDTO)
async def health() -> HealthDTO:
    probe = youtube.health()
    return HealthDTO(
        status="ok",
        version=VERSION,
        ytDlp=probe["ytDlp"],
        cookies=probe["cookies"],
        proxy=probe["proxy"],
        poToken=probe["poToken"],
        potScript=probe["potScript"],
        potBaseurl=probe["potBaseurl"],
        impersonate=probe["impersonate"],
        cacheDir=str(settings.cache_dir),
        cacheTtlSeconds=settings.cache_ttl_seconds,
    )


@app.post("/api/cookies", response_model=CookieResultDTO)
async def set_cookies(payload: CookiesDTO) -> CookieResultDTO:
    target = settings.cache_dir.parent / "cookies.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    content = payload.content
    if "youtube.com" not in content and "# Netscape" not in content:
        raise HTTPException(status_code=400, detail="No parece un cookies.txt de Netscape")
    target.write_text(content, encoding="utf-8")
    youtube.cookies_file = target
    logger.info("cookies actualizadas (%d bytes)", len(content))
    return CookieResultDTO(stored=True, bytes=len(content))


@app.delete("/api/cookies", response_model=CookieResultDTO)
async def clear_cookies() -> CookieResultDTO:
    current = youtube.cookies_file
    if current and current.exists():
        current.unlink()
    youtube.cookies_file = None
    return CookieResultDTO(stored=False, bytes=0)


_VIDEO_ID_RE = VIDEO_ID_RE


@app.get("/api/debug/{video_id}")
async def debug(video_id: str, client: str | None = Query(default=None)) -> dict:
    """Telemetría de yt-dlp para diagnosticar el bot-check (no descarga nada).

    `client` (opcional) fuerza un único `player_client`, p. ej. `web_safari`.
    """
    if not _VIDEO_ID_RE.match(video_id):
        raise HTTPException(status_code=400, detail="video_id invalido")
    clients = (client,) if client else None
    return await _offload(youtube.diagnose, video_id, clients)


@app.get("/api/search", response_model=SearchDTO)
async def search(
    q: str = Query(min_length=1),
    page: int = Query(default=0, ge=0),
    page_size: int = Query(default=20, ge=1, le=50),
) -> SearchDTO:
    try:
        videos, has_next = await _offload(youtube.search, q, page, page_size)
    except YouTubeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    return SearchDTO(
        query=q,
        videos=videos,
        nextPage=str(page + 1) if has_next else None,
        hasNextPage=has_next,
    )


@app.get("/api/video/{video_id}", response_model=VideoDTO)
async def video(video_id: str) -> VideoDTO:
    try:
        return await _offload(youtube.video_info, video_id)
    except YouTubeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.get("/api/qualities/{video_id}", response_model=list[QualityDTO])
async def qualities(video_id: str) -> list[QualityDTO]:
    try:
        return await _offload(youtube.qualities, video_id)
    except YouTubeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.get("/api/related/{video_id}", response_model=list[VideoDTO])
async def related(video_id: str) -> list[VideoDTO]:
    # yt-dlp no expone "relacionados"; el cliente los pide al resolver local,
    # que es barato y no necesita el servidor. Se declara como no soportado en
    # vez de devolver una lista vacía que parecería un resultado real.
    raise HTTPException(status_code=501, detail="El servidor no ofrece videos relacionados")


@app.get("/api/media/{video_id}")
async def media(
    video_id: str,
    request: Request,
    quality: str = Query(default=quality_mod.BEST_ID),
    type: str = Query(default="video"),
) -> FileResponse:
    if type not in ("video", "audio"):
        raise HTTPException(status_code=400, detail=f"tipo desconocido: {type!r}")
    try:
        quality_mod.selector_for(quality, type)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    kind = type
    key = hashlib.sha1(f"{video_id}|{quality}|{kind}".encode()).hexdigest()
    extension = "m4a" if kind == "audio" else "mp4"
    media_type = "audio/mp4" if kind == "audio" else "video/mp4"

    def produce() -> Path:
        logger.info("materializando %s %s/%s", video_id, quality, kind)
        return youtube.download(video_id, quality, kind, cache.directory)

    try:
        path = await cache.ensure(key, produce)
    except YouTubeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    return FileResponse(
        path,
        media_type=media_type,
        filename=safe_filename(video_id, extension),
        headers={"Accept-Ranges": "bytes"},
    )


async def _offload(fn, *args):
    import asyncio

    return await asyncio.to_thread(fn, *args)
