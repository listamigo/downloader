"""Caché de medios materializados con lock por clave.

`HttpFileDownloader` (en el móvil) sondea con `Range: bytes=0-0` y luego lanza
varios segmentos en paralelo. Si cada petición materializara el medio, una sola
descarga dispararía cinco bajadas completas en el servidor. Aquí la primera
petición produce el fichero y las demás esperan al mismo lock y lo reutilizan.
"""

from __future__ import annotations

import asyncio
import os
import time
from pathlib import Path
from typing import Callable


class MediaCache:
    def __init__(self, directory: Path, ttl_seconds: int) -> None:
        self._dir = directory
        self._ttl = ttl_seconds
        self._locks: dict[str, asyncio.Lock] = {}
        self._guard = asyncio.Lock()

    @property
    def directory(self) -> Path:
        return self._dir

    async def ensure(self, key: str, produce: Callable[[], Path]) -> Path:
        """Devuelve el fichero para `key`, produciéndolo si hace falta.

        `produce` corre fuera del event loop (yt-dlp es bloqueante) y devuelve
        la ruta de un fichero temporal. Se mueve atómicamente al destino.
        """
        self._dir.mkdir(parents=True, exist_ok=True)
        path = self._dir / key
        if self._is_fresh(path):
            return path

        lock = await self._lock_for(key)
        async with lock:
            if self._is_fresh(path):
                return path
            produced = await asyncio.to_thread(produce)
            os.replace(produced, path)
            self._locks.pop(key, None)
            return path

    def purge_expired(self) -> int:
        """Borra ficheros caducados. Devuelve cuántos eliminó."""
        if not self._dir.exists():
            return 0
        removed = 0
        for item in self._dir.iterdir():
            if not item.is_file():
                continue
            if not self._is_fresh(item):
                try:
                    item.unlink()
                    removed += 1
                except OSError:
                    pass
        return removed

    def _is_fresh(self, path: Path) -> bool:
        try:
            age = time.time() - path.stat().st_mtime
        except FileNotFoundError:
            return False
        return age < self._ttl

    async def _lock_for(self, key: str) -> asyncio.Lock:
        async with self._guard:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock
