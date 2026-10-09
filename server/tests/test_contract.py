"""Test de contrato de la API. Se salta si FastAPI/yt-dlp no están instalados
(el núcleo puro se prueba sin dependencias en `test_quality`/`test_naming`).

No toca la red: parchea el adaptador de YouTube.
"""

from __future__ import annotations

import unittest
from pathlib import Path

try:
    from fastapi.testclient import TestClient

    from app import main

    AVAILABLE = True
except Exception:  # pragma: no cover - depende del entorno
    AVAILABLE = False


@unittest.skipUnless(AVAILABLE, "fastapi/yt-dlp no instalados en este entorno")
class ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self._client = TestClient(main.app)
        self._client.__enter__()

    def tearDown(self) -> None:
        self._client.__exit__(None, None, None)

    def test_health(self):
        response = self._client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "ok")
        self.assertIn("ytDlp", body)

    def test_health_reports_impersonate_plan(self):
        response = self._client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        # El plan por defecto impersona Chrome: es la palanca contra el reto
        # bot que YouTube lanza a las IPs de datacenter (Railway).
        self.assertIn("chrome", body["impersonate"])

    def test_related_is_not_supported(self):
        response = self._client.get("/api/related/dQw4w9WgXcQ")
        self.assertEqual(response.status_code, 501)

    def test_media_rejects_bad_type(self):
        response = self._client.get("/api/media/abc?quality=best&type=banana")
        self.assertEqual(response.status_code, 400)

    def test_media_rejects_crossed_quality_and_type(self):
        response = self._client.get("/api/media/abc?quality=v:1080:30&type=audio")
        self.assertEqual(response.status_code, 400)

    def test_media_serves_ranges(self):
        payload = b"0123456789"

        def fake_download(video_id, quality_id, kind, work_dir: Path) -> Path:
            target = work_dir / "fake.tmp"
            target.write_bytes(payload)
            return target

        main.youtube.download = fake_download
        try:
            response = self._client.get(
                "/api/media/abc?quality=best&type=video",
                headers={"Range": "bytes=0-3"},
            )
        finally:
            del main.youtube.download
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.content, b"0123")
        self.assertEqual(response.headers.get("content-range"), "bytes 0-3/10")


if __name__ == "__main__":
    unittest.main()
