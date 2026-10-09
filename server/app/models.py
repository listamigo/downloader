"""Esquema de la API (Pydantic v2).

Espeja el modelo de dominio de la app para que el contrato sea explícito y
validado, y para que OpenAPI (`/docs`) sirva de documentación viva.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class VideoDTO(BaseModel):
    videoId: str
    title: str
    channelName: str = ""
    duration: str = ""
    viewCount: str = ""
    uploadDate: str = ""
    thumbnailUrl: str = ""
    isLive: bool = False


class SearchDTO(BaseModel):
    query: str
    videos: list[VideoDTO]
    nextPage: str | None = None
    hasNextPage: bool = False


class QualityDTO(BaseModel):
    id: str
    label: str
    format: str
    height: int | None = None
    fps: int | None = None
    videoCodec: str | None = None
    audioCodec: str | None = None
    fileSize: int | None = None
    isAudioOnly: bool = False


class HealthDTO(BaseModel):
    status: str
    version: str
    ytDlp: str
    cookies: bool
    proxy: bool
    poToken: bool
    potScript: bool
    potBaseurl: bool
    cacheDir: str
    cacheTtlSeconds: int


class CookiesDTO(BaseModel):
    content: str = Field(min_length=1, description="Contenido Netscape cookies.txt")


class CookieResultDTO(BaseModel):
    stored: bool
    bytes: int


class ErrorDTO(BaseModel):
    detail: str
