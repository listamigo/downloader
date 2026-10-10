"""Tests de la telemetría de debug (`/api/debug`): redacción de secretos y
sonda del proveedor de PO sin tocar la red."""

from __future__ import annotations

import unittest

try:
    from app.youtube import VIDEO_ID_RE, _probe_provider, _redact  # type: ignore

    AVAILABLE = True
except Exception:  # pragma: no cover - depende del entorno
    AVAILABLE = False

try:
    from app.config import Settings  # type: ignore

    CONFIG_AVAILABLE = True
except Exception:  # pragma: no cover
    CONFIG_AVAILABLE = False

try:
    from fastapi import HTTPException  # type: ignore

    from app.main import check_debug_auth  # type: ignore

    AUTH_AVAILABLE = True
except Exception:  # pragma: no cover
    AUTH_AVAILABLE = False


@unittest.skipUnless(AVAILABLE, "dependencias del server no instaladas")
class RedactTest(unittest.TestCase):
    def test_enmascara_cookies_de_sesion(self) -> None:
        line = "Set-Cookie: SID=thisIsASecretValue123; expires=..."
        out = _redact(line)
        self.assertNotIn("thisIsASecretValue123", out)
        self.assertIn("SID=***", out)

    def test_enmascara_st_tokens(self) -> None:
        out = _redact("cookie ST-1d9jt3w=abcDEF123ghi posted")
        self.assertNotIn("abcDEF123ghi", out)
        self.assertIn("ST-1d9jt3w=***", out)

    def test_enmascara_tokens_largos(self) -> None:
        token = "A" * 50
        out = _redact(f"visitor_data={token}")
        self.assertNotIn(token, out)
        self.assertIn("***", out)

    def test_deja_texto_normal(self) -> None:
        self.assertEqual(_redact("[debug] Extracting URL: https://x/watch?v=abc"), "[debug] Extracting URL: https://x/watch?v=abc")


@unittest.skipUnless(AVAILABLE, "dependencias del server no instaladas")
class ProbeProviderTest(unittest.TestCase):
    def test_sin_baseurl(self) -> None:
        self.assertEqual(_probe_provider(None), {"configured": False, "reachable": False})

    def test_baseurl_inalcanzable(self) -> None:
        # Puerto sin nada escuchando: debe reportar reachable=False, sin lanzar.
        probe = _probe_provider("http://127.0.0.1:9")
        self.assertTrue(probe["configured"])
        self.assertFalse(probe["reachable"])
        self.assertIn("error", probe)


@unittest.skipUnless(AVAILABLE, "dependencias del server no instaladas")
class VideoIdRegexTest(unittest.TestCase):
    def test_acepta_ids_validos(self) -> None:
        self.assertTrue(VIDEO_ID_RE.match("9bZkp7q19f0"))
        self.assertTrue(VIDEO_ID_RE.match("dQw4w9WgXcQ"))

    def test_rechaza_basura(self) -> None:
        self.assertIsNone(VIDEO_ID_RE.match("../../etc/passwd"))
        self.assertIsNone(VIDEO_ID_RE.match("short"))


@unittest.skipUnless(AUTH_AVAILABLE, "fastapi/app.main no disponibles")
class DebugAuthTest(unittest.TestCase):
    def test_sin_token_configurado_bloquea(self) -> None:
        # Fail-closed: sin DEBUG_TOKEN el endpoint queda apagado.
        with self.assertRaises(HTTPException) as ctx:
            check_debug_auth(None, "loquesea")
        self.assertEqual(ctx.exception.status_code, 503)

    def test_sin_provided(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            check_debug_auth("secreto", None)
        self.assertEqual(ctx.exception.status_code, 401)

    def test_token_incorrecto(self) -> None:
        with self.assertRaises(HTTPException) as ctx:
            check_debug_auth("secreto", "otro")
        self.assertEqual(ctx.exception.status_code, 401)

    def test_token_correcto_pasa(self) -> None:
        check_debug_auth("secreto", "secreto")  # no debe lanzar


@unittest.skipUnless(CONFIG_AVAILABLE, "app.config no disponible")
class ConfigDebugTokenTest(unittest.TestCase):
    def test_default_none(self) -> None:
        self.assertIsNone(Settings.from_env({}).debug_token)

    def test_lee_env(self) -> None:
        self.assertEqual(Settings.from_env({"DEBUG_TOKEN": "abc123"}).debug_token, "abc123")
