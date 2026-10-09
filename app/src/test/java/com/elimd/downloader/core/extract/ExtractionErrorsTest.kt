package com.elimd.downloader.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * El bloqueo de YouTube es el fallo que mas se repite en la app. Si el mensaje no
 * dice que hacer, el usuario solo puede reinstalar la app.
 *
 * Ojo con la direccion del diagnostico: antes de medirlo, el bloqueo se
 * atribuia a la IP (ver ESTADO_PROYECTO.md, seccion 5). La medicion lo refuto:
 * no era la IP ni el cliente de InnerTube, era la falta de sesion. Por eso este
 * test comprueba tambien que el mensaje **no** culpa a la IP: si alguien
 * reintroduce ese diagnostico equivocado, el test lo detecta.
 */
class ExtractionErrorsTest {

    @Test
    fun `el bloqueo de YouTube dice que hay que renovar la sesion`() {
        val message = explain(SignInConfirmNotBotException("Sign in to confirm that you're not a bot"))

        assertTrue("no dice que hacer: $message", message.contains("cookies"))
        assertTrue("no explica la causa: $message", message.contains("sesion"))
        assertFalse(
            "vuelve a culpar a la IP, que ya se descarto: $message",
            message.contains("IP"),
        )
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