"""Adaptador yt-dlp: los extractor-args del PO token deben ir por los claves
NUEVAS de yt-dlp >= 2025.05.22 (por proveedor: youtubepot-bgutilhttp /
youtubepot-bgutilscript), no por los deprecados `youtube:getpot_bgutil_*`.

Se salta si yt-dlp o el plugin de PO no están instalados.
"""

from __future__ import annotations

import unittest

try:
    from app.config import Settings
    from app.youtube import YouTube
    from yt_dlp.version import __version__ as YTDLP_VERSION  # noqa: F401
    from yt_dlp_plugins.extractor import getpot_bgutil_http  # noqa: F401

    AVAILABLE = True
except Exception:  # pragma: no cover - depende del entorno
    AVAILABLE = False


def _base_opts(pot_baseurl: str | None = None, pot_script: str | None = None) -> dict:
    settings = Settings.from_env(
        {
            "MEDIA_CACHE_DIR": "/tmp/x",
            **({"YTDLP_POT_BASEURL": pot_baseurl} if pot_baseurl else {}),
            **({"YTDLP_POT_SCRIPT": pot_script} if pot_script else {}),
        }
    )
    youtube = YouTube(settings)
    return youtube._base_opts(("default",))


@unittest.skipUnless(AVAILABLE, "yt-dlp o el plugin de PO no instalados")
class PotExtractorArgsTest(unittest.TestCase):
    def test_baseurl_va_del_proveedor(self) -> None:
        opts = _base_opts(pot_baseurl="http://127.0.0.1:4416")
        extractor_args = opts["extractor_args"]
        # El plugin 1.3.2 lee base_url del proveedor por ESTA clave.
        self.assertEqual(
            extractor_args.get("youtubepot-bgutilhttp"),
            {"base_url": ["http://127.0.0.1:4416"]},
        )
        # Los args deprecados no deben aparecer.
        self.assertNotIn("getpot_bgutil_baseurl", extractor_args.get("youtube", {}))

    def test_script_va_como_server_home(self) -> None:
        opts = _base_opts(pot_script="/opt/pot-provider/server")
        extractor_args = opts["extractor_args"]
        # `server_home` = HOME del repo clonado (carpeta con build/).
        self.assertEqual(
            extractor_args.get("youtubepot-bgutilscript"),
            {"server_home": ["/opt/pot-provider/server"]},
        )

    def test_po_token_manual_sigue_en_youtube(self) -> None:
        settings = Settings.from_env({"MEDIA_CACHE_DIR": "/tmp/x", "YT_PO_TOKEN": "cliente+token"})
        opts = YouTube(settings)._base_opts(("default",))
        self.assertIn("po_token", opts["extractor_args"]["youtube"])