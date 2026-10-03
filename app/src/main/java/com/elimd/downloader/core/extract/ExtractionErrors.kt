package com.elimd.downloader.core.extract

import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

/**
 * Que puede hacer quien recibe el fallo.
 *
 * El bloqueo de YouTube es el fallo mas frecuente de la app y el menos
 * explicito: sin esto, el usuario ve `SignInConfirmNotBotException` y no tiene
 * forma de saber que tiene que hacer. Y el mensaje equivocado es peor que
 * ninguno, porque "este video no esta disponible" manda a buscar un video que si
 * lo esta (ver ESTADO_PROYECTO.md, seccion 5).
 */
internal fun explain(error: Throwable): String = when (error) {
    is SignInConfirmNotBotException ->
        "YouTube ha pedido confirmar que no eres un bot (LOGIN_REQUIRED). Suele ser " +
            "temporal: reintenta en unos segundos. Si se repite, comprueba que las " +
            "cookies siguen en su sitio y estan vigentes; sin sesion acreditada, " +
            "esta peticion se trata como anonima y se bloquea antes."

    is AgeRestrictedContentException ->
        "El video es para mayores de edad y no se puede descargar con esta cuenta."

    is PrivateContentException ->
        "El video es privado: solo se puede descargar con una cuenta que tenga acceso."

    else -> error.message ?: error.javaClass.simpleName
}