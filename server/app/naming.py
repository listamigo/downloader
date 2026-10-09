"""Saneado de nombres para `Content-Disposition`.

Puro y con tests: el nombre que se anuncia al cliente no puede contener
separadores de ruta ni caracteres de control, ni crecer sin límite.
"""

from __future__ import annotations

import re

_UNSAFE = re.compile(r'[\\/:*?"<>|\x00-\x1f]')
_WHITESPACE = re.compile(r"\s+")

MAX_STEM_LENGTH = 120


def safe_filename(title: str, extension: str) -> str:
    """Nombre de archivo seguro a partir del título y una extensión."""
    stem = _UNSAFE.sub("_", (title or "").strip())
    stem = _WHITESPACE.sub(" ", stem).strip(" .")
    if not stem:
        stem = "media"
    if len(stem) > MAX_STEM_LENGTH:
        stem = stem[:MAX_STEM_LENGTH].rstrip()
    suffix = extension.lstrip(".").lower() or "bin"
    return f"{stem}.{suffix}"
