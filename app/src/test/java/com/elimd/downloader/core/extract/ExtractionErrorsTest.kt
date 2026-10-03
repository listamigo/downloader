package com.elimd.downloader.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * El bloqueo por IP es el fallo que mas se repite en la app. Si el mensaje no
 * dice que hacer, el usuario solo puede renormalizar la app.
 */
class ExtractionErrorsTest {

    @Test
    fun `el bloqueo de YouTube dice que va por IP`() {
        val message = explain(SignInConfirmNotBotException("Sign in to confirm that you're not a bot"))

        assertTrue("no menciona la IP: $message", message.contains("IP"))
        assertTrue("no dice que hacer: $message", message.contains("red"))
        assertTrue("no habla de cookies: $message", message.contains("cookies"))
    }

    @Test
    fun `un video privado no se disfraza de error generico`() {
        assertTrue(explain(PrivateContentException("x")).contains("privado"))
    }

    @Test
    fun `un fallo desconocido conserva su mensaje`() {
        assertEquals("se rompio algo", explain(ExtractionException("se rompio algo")))
    }

    @Test
    fun `un fallo sin mensaje al menos dice que clase es`() {
        assertEquals("NullPointerException", explain(NullPointerException()))
    }
}