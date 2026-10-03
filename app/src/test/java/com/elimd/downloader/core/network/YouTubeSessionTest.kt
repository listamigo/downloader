package com.elimd.downloader.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cookies son credenciales de la cuenta del usuario. Aqui hay dos riesgos
 * opuestos y los dos rompen la app:
 *
 *  1. Que se envie una cookie que no sirve -> la sesion parece activa pero no
 *     lo esta, y el fallo vuelve a ser `LOGIN_REQUIRED` sin explicacion.
 *  2. Que la cookie se envie a un host que no es YouTube -> fuga de credencial
 *     a un tercero sin ningun motivo.
 */
class YouTubeSessionTest {

    // ---------------- parseo ----------------

    @Test
    fun `lee las cookies de una exportacion normal`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	2147483647	SID	valor-sid
            .youtube.com	TRUE	/	TRUE	2147483647	HSID	valor-hsid
            """.trimIndent()
        )

        assertEquals("valor-sid", cookies["SID"])
        assertEquals("valor-hsid", cookies["HSID"])
    }

    @Test
    fun `lee las cookies HttpOnly que vienen comentadas`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t2147483647\tAPISID\tvalor-apisid"
        )

        assertEquals("valor-apisid", cookies["APISID"])
    }

    @Test
    fun `descarta las cookies de otros dominios`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            """
            .example.com	TRUE	/	TRUE	2147483647	SID	ajena
            .youtube.com	TRUE	/	TRUE	2147483647	SID	propia
            """.trimIndent()
        )

        assertEquals("propia", cookies["SID"])
        assertEquals(1, cookies.size)
    }

    @Test
    fun `descarta las cookies caducadas`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            ".youtube.com\tTRUE\t/\tTRUE\t1000000000\tSID\tvieja"
        )

        assertTrue(cookies.isEmpty())
    }

    @Test
    fun `una caducidad a cero significa cookie de sesion y se conserva`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            ".youtube.com\tTRUE\t/\tFALSE\t0\tSID\tde-sesion"
        )

        assertEquals("de-sesion", cookies["SID"])
    }

    @Test
    fun `ignora lineas mal formadas en vez de fallar`() {
        val cookies = YouTubeSession.parseNetscapeCookies(
            """
            esto no es una cookie
            .youtube.com	TRUE	/
            .youtube.com	TRUE	/	TRUE	2147483647	SID	buena
            """.trimIndent()
        )

        assertEquals("buena", cookies["SID"])
    }

    // ---------------- sesion valida ----------------

    @Test
    fun `una cabecera con cookies de sesion se construye`() {
        val header = YouTubeSession.buildHeader(
            mapOf("SID" to "a", "SAPISID" to "b", "VISITOR_INFO1_LIVE" to "c")
        )

        assertEquals("SID=a; SAPISID=b; VISITOR_INFO1_LIVE=c", header)
    }

    @Test
    fun `un fichero sin cookies de sesion no produce cabecera`() {
        // Un cookies.txt de visitante anonimo no sirve para nada. Devolver una
        // cabecera "vacia de sesion" haria creer que hay sesion activa.
        val header = YouTubeSession.buildHeader(
            mapOf("VISITOR_INFO1_LIVE" to "x", "PREF" to "y")
        )

        assertNull(header)
    }

    @Test
    fun `una cookie __Secure sufijo tambien cuenta como sesion`() {
        assertTrue(
            YouTubeSession.buildHeader(mapOf("__Secure-3PAPISID" to "x")) != null
        )
    }

    // ---------------- politica de envio ----------------

    @Test
    fun `la cookie se envia a youtube`() {
        val header = YouTubeSession.cookieHeaderFor("www.youtube.com", "SID=a")

        assertEquals("SID=a", header)
    }

    @Test
    fun `la cookie NO se envia al CDN de video`() {
        // El medio se baja de googlevideo.com. Mandar ahi una credencial de la
        // cuenta del usuario seria una fuga sin ningun motivo.
        assertNull(YouTubeSession.cookieHeaderFor("rr3---sn-abc.googlevideo.com", "SID=a"))
    }

    @Test
    fun `la cookie NO se envia a un host que se le parece`() {
        assertNull(YouTubeSession.cookieHeaderFor("youtube.com.attacker.example", "SID=a"))
        assertNull(YouTubeSession.cookieHeaderFor("notyoutube.com", "SID=a"))
    }

    @Test
    fun `sin sesion no se inventa cabecera`() {
        assertNull(YouTubeSession.cookieHeaderFor("www.youtube.com", null))
        assertNull(YouTubeSession.cookieHeaderFor(null, "SID=a"))
    }

    @Test
    fun `solo se reconocen hosts de YouTube`() {
        assertTrue(YouTubeSession.isYouTubeHost("youtube.com"))
        assertTrue(YouTubeSession.isYouTubeHost("www.youtube.com"))
        assertTrue(YouTubeSession.isYouTubeHost("m.youtube.com"))
        assertFalse(YouTubeSession.isYouTubeHost("googlevideo.com"))
        assertFalse(YouTubeSession.isYouTubeHost("google.com"))
    }

    @Test
    fun `la cabecera de autorizacion tiene el formato que acepta Google`() {
        // Valor de referencia calculado por fuera con el algoritmo de yt-dlp
        // (sha1("<unix> <SAPISID> <origin>")). Si el formato cambia, este test
        // falla: y si cambia, YouTube vuelve a tratar la peticion como anonima.
        val header = YouTubeSession.buildSapisidHashAuthorization(
            cookies = mapOf("SAPISID" to "valor-de-prueba"),
            origin = "https://www.youtube.com",
            timestampSeconds = 1767225600L
        )
        assertEquals(
            "SAPISIDHASH 1767225600_b22f97dadfdfd58fe17f1d674765a2d191659922",
            header
        )
    }

    @Test
    fun `cada cookie de SameSite lleva su propio esquema`() {
        val header = YouTubeSession.buildSapisidHashAuthorization(
            cookies = mapOf(
                "SAPISID" to "a",
                "__Secure-1PAPISID" to "b",
                "__Secure-3PAPISID" to "c"
            ),
            origin = "https://www.youtube.com",
            timestampSeconds = 1767225600L
        )!!
        assertEquals(3, header.split(" ").size / 2)
        assertTrue(header.startsWith("SAPISIDHASH "))
        assertTrue(header.contains(" SAPISID1PHASH "))
        assertTrue(header.contains(" SAPISID3PHASH "))
    }

    @Test
    fun `el origen entra en el hash`() {
        val com = YouTubeSession.buildSapisidHashAuthorization(
            mapOf("SAPISID" to "x"), "https://www.youtube.com", 1L)
        val music = YouTubeSession.buildSapisidHashAuthorization(
            mapOf("SAPISID" to "x"), "https://music.youtube.com", 1L)
        assertNotEquals(com, music)
    }

    @Test
    fun `sin cookie de autenticacion no se inventa cabecera`() {
        assertNull(
            YouTubeSession.buildSapisidHashAuthorization(
                mapOf("SID" to "x"), "https://www.youtube.com", 1L
            )
        )
        assertNull(
            YouTubeSession.buildSapisidHashAuthorization(
                emptyMap(), "https://www.youtube.com", 1L
            )
        )
    }

    @Test
    fun `la autorizacion tampoco viaja al CDN ni a un falso YouTube`() {
        assertNull(
            YouTubeSession.authorizationHeaderFor("rr3---sn-abc.googlevideo.com", "SAPISIDHASH 1_x")
        )
        assertNull(YouTubeSession.authorizationHeaderFor("youtube.com.attacker.example", "SAPISIDHASH 1_x"))
        assertNull(YouTubeSession.authorizationHeaderFor(null, "SAPISIDHASH 1_x"))
        assertNull(YouTubeSession.authorizationHeaderFor("www.youtube.com", null))
        assertEquals(
            "SAPISIDHASH 1_x",
            YouTubeSession.authorizationHeaderFor("www.youtube.com", "SAPISIDHASH 1_x")
        )
    }
}
