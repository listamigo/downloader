package com.elimd.downloader.core.extract

import android.util.Log
import com.elimd.downloader.BuildConfig
import com.elimd.downloader.core.network.YouTubeSession
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * Puente entre el extractor y OkHttp.
 *
 * NewPipeExtractor necesita un `Downloader` para resolver las URLs firmadas de
 * YouTube. Sin esto no puede descifrar los streams, porque la respuesta del
 * servidor cambia en cada peticion.
 *
 * Este es el **unico** punto por el que pasan todas las peticiones del
 * extractor, asi que es tambien donde se inyectan las cookies de sesion: sin
 * ellas, una IP marcada recibe `LOGIN_REQUIRED` en lugar de los streams.
 */
class OkHttpNewPipeDownloader(
    private val client: OkHttpClient,
    private val session: YouTubeSession
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkRequest.Builder()
            .url(request.url().toHttpUrl())

        val headers = request.headers().toMutableMap()
        request.localization()?.let { localization ->
            headers.putAll(Request.getHeadersFromLocalization(localization))
        }
        headers.forEach { (key, values) ->
            if (values.isNotEmpty()) {
                builder.header(key, values.joinToString(", "))
            }
        }

        // La cabecera `Cookie` se calcula aqui y no antes a proposito: depende
        // del host de cada peticion, y solo debe viajar a YouTube.
        val host = request.url().toHttpUrl().host
        YouTubeSession.cookieHeaderFor(host, session.cookieHeader())?.let {
            builder.header("Cookie", it)

            // Y la van acompanada del `Authorization` que demuestra que esas
            // cookies se han leido. Sin el hash, YouTube ignora la sesion y trata
            // la peticion como anonima: por eso fallaba con `LOGIN_REQUIRED` y
            // por eso la unica salida parecia cambiar de IP. El origen es el de
            // la propia peticion, que es lo que Google usa para calcularlo.
            val origin = "${request.url().toHttpUrl().scheme}://$host"
            YouTubeSession.authorizationHeaderFor(host, session.authorizationHeader(origin))
                ?.let { hash -> builder.header("Authorization", hash) }
        }

        val body = request.dataToSend()
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: ByteArray(0)).toRequestBody(null))
            "HEAD" -> builder.head()
            else -> throw IOException("Metodo HTTP no soportado: ${request.httpMethod()}")
        }

        client.newCall(builder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            logPlayability(response.request.url.toString(), builder.build().header("Authorization") != null, rawBody)
            val responseBody = SearchResponseSanitizer.sanitizeIfNeeded(request.url(), rawBody)
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString()
            )
        }
    }

    /**
     * Registra por qué el extractor recibe un bloqueo.
     *
     * YouTube responde `200 OK` con `playabilityStatus` diciendo que no, así que
     * sin esto el fallo no aparece por ninguna parte: solo se ve un
     * `SignInConfirmNotBotException` mil lineas despues. Se registra solo
     * cuando el estado no es bueno, y **sin ningun valor de cookie**: el
     * `yes` dice si la cabecera `Authorization` llego a enviarse, que es lo que
     * hay que distinguir entre "no hay sesion" y "la sesion no cuenta".
     */
    private fun logPlayability(url: String, sentAuthorization: Boolean, body: String) {
        if (!DEBUG_PATHS) return
        val status = PLAYABILITY_STATUS.find(body)?.groupValues?.getOrNull(1)
        val reason = PLAYABILITY_REASON.find(body)?.groupValues?.getOrNull(1)
        Log.w(
            TAG,
            "player <- ${responseLabel(url)} status=${status ?: "null"} " +
                "reason=${reason ?: "null"} " +
                "json=${body.contains("playabilityStatus")} bytes=${body.length} " +
                "cookie=${session.cookieHeader() != null} authorization=$sentAuthorization"
        )
    }

    private fun responseLabel(url: String): String =
        Regex("/youtubei/v1/([a-z/]+)").find(url)?.groupValues?.getOrNull(1) ?: url.substringAfter("//").substringBefore('?').take(60)

    companion object {
        private const val TAG = "ExtractorHttp"

        /**
         * El diagnostico de `playabilityStatus` solo se registra en depuracion.
         *
         * En release escribe en logcat el motivo del bloqueo de YouTube; aunque
         * no filtra cookies, es trafico del usuario que no tiene por que quedar
         * en registros de produccion. Con `BuildConfig.DEBUG` la decision la
         * toma el compilador y no hay rama que se olvide de cambiar.
         */
        private val DEBUG_PATHS = BuildConfig.DEBUG

        private val PLAYABILITY_STATUS = "\"playabilityStatus\"\\s*:\\s*\\{\\s*\"status\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        private val PLAYABILITY_REASON = "\"reason\"\\s*:\\s*\"([^\"]+)\"".toRegex()

        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
