package com.elimd.downloader.core.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sesión de YouTube tomada de las cookies de un navegador.
 *
 * Desde una IP marcada, YouTube no responde al cliente sino a la sesión: por eso
 * hace falta una cuenta con sesión iniciada. Las cookies se exportan del
 * navegador al formato `cookies.txt` y se dejan en un fichero del móvil; la app
 * solo las lee, nunca las escribe en el repositorio ni las registra en el log.
 *
 * Este objeto **no** es un plano para la cookie: decide qué peticiones llevan la
 * cabecera, y esa política está en [cookieHeaderFor].
 */
@Singleton
class YouTubeSession @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    private var cachedHeader: String? = null

    /**
     * Cookies de sesión ya parseadas.
     *
     * Se guardan aparte de la cabecera porque la cabecera `Cookie` es estable y
     * se puede cachear, pero la de `Authorization` lleva dentro un timestamp y
     * hay que rehacerla en cada petición (ver [authorizationHeader]).
     */
    @Volatile
    private var cachedCookies: Map<String, String> = emptyMap()

    @Volatile
    private var loaded = false

    /**
     * La cabecera `Cookie` para las peticiones a YouTube, o `null` si no hay
     * sesión.
     *
     * Se cachea: se lee en cada resolución y no tiene sentido releer el disco.
     */
    fun cookieHeader(): String? {
        ensureLoaded()
        return cachedHeader
    }

    /**
     * Cabecera `Authorization` que **acompaña** a las cookies de sesión, o `null`.
     *
     * Sin esto las cookies no cuentan. Google exige que quien envía `SAPISID`
     * demuestre que lo ha leído calculando un hash con su valor y una marca de
     * tiempo; si la petición llega con la cookie pero sin el hash, el servidor la
     * descarta y trata la petición como anónima. Ahí aparece el texto
     * "blocked anonymous watch access" y el `LOGIN_REQUIRED`, que parece un
     * bloqueo por IP pero en realidad es una sesión que no se ha acreditado.
     *
     * **No se cachea**: lleva dentro la hora actual y YouTube compara el
     * timestamp con su propio reloj. Es un SHA-1, así que rehacerlo en cada
     * petición no cuesta nada.
     */
    fun authorizationHeader(origin: String): String? {
        ensureLoaded()
        if (cachedCookies.isEmpty()) return null
        return buildSapisidHashAuthorization(
            cookies = cachedCookies,
            origin = origin,
            timestampSeconds = System.currentTimeMillis() / 1000
        )
    }

    private fun ensureLoaded() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val cookies = loadCookies()
                cachedCookies = cookies
                cachedHeader = buildHeader(cookies)
                loaded = true
            }
        }
    }

    /** Obliga a releer el fichero, para cuando el usuario cambia las cookies. */
    fun reload() {
        synchronized(this) {
            cachedCookies = loadCookies()
            cachedHeader = buildHeader(cachedCookies)
            loaded = true
        }
    }

    /**
     * Dónde se busca el `cookies.txt`.
     *
     * El directorio externo propio se usa porque `adb push` escribe ahí sin
     * permisos especiales, y el interno para cuando se copia desde la app.
     */
    fun candidateFiles(): List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.resolve(COOKIE_FILE_NAME),
        File(context.filesDir, COOKIE_FILE_NAME),
        File(context.filesDir, "session/$COOKIE_FILE_NAME")
    )

    private fun loadCookies(): Map<String, String> {
        val file = candidateFiles().firstOrNull { it.exists() && it.length() > 0 }
        if (file == null) {
            Log.i(TAG, "Sin fichero de cookies: se usara la sesion anonima")
            return emptyMap()
        }

        val cookies = parseNetscapeCookies(file.readText())
        if (buildHeader(cookies) == null) {
            Log.w(
                TAG,
                "El fichero ${file.name} no contiene cookies de sesion de YouTube " +
                    "(nombres presentes: ${cookies.keys.take(8)}). Se ignora."
            )
            return emptyMap()
        }

        Log.i(TAG, "Sesion cargada de ${file.name}: ${cookies.size} cookies de youtube.com")
        return cookies
    }

    companion object {
        private const val TAG = "YouTubeSession"
        private const val COOKIE_FILE_NAME = "cookies.txt"

        /**
         * Cabecera `Cookie` para [host], o `null`.
         *
         * **Solo para dominios de YouTube.** Las cookies de sesión no deben
         * viajar a `googlevideo.com`, que es el CDN donde bajan los medios: ese
         * host es de terceros y aunque el tráfico va cifrado, mandar ahí una
         * credencial de tu cuenta es una fuga que no hace falta para nada.
         */
        fun cookieHeaderFor(host: String?, header: String?): String? {
            if (host == null || header == null) return null
            return if (isYouTubeHost(host)) header else null
        }

        /**
         * Cabecera `Authorization` para [host], o `null`.
         *
         * Misma politica que [cookieHeaderFor] y por el mismo motivo: el hash es
         * una credencial de tu cuenta y no debe viajar al CDN ni a un host que
         * solo se le parezca a YouTube.
         */
        fun authorizationHeaderFor(host: String?, header: String?): String? {
            if (host == null || header == null) return null
            return if (isYouTubeHost(host)) header else null
        }

        /** `youtube.com` y sus subdominios; nada más. */
        fun isYouTubeHost(host: String): Boolean {
            val h = host.lowercase().removePrefix("www.")
            return h == "youtube.com" || h.endsWith(".youtube.com") ||
                h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com")
        }

        /**
         * Cookies de las que depende una sesión real de YouTube.
         *
         * Sin ninguna de estas, el `cookies.txt` es de un visitante anónimo y no
         * sirve para nada: mejor decirlo claro que fingir que hay sesión.
         */
        private val SESSION_COOKIES = setOf(
            "SID", "HSID", "SSID", "APISID", "SAPISID",
            "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID"
        )

        private val GOOGLE_COOKIE_DOMAIN = "google.com"

        /**
         * Parsea el formato `cookies.txt` de Netscape.
         *
         * Cada línea: `dominio`, `incluirSubdominios`, `ruta`, `seguro`,
         * `caducidad`, `nombre`, `valor`. Las líneas de HttpOnly vienen
         * comentadas con `#HttpOnly_`, y los comentarios empiezan por `#`.
         */
        fun parseNetscapeCookies(content: String): Map<String, String> {
            val now = System.currentTimeMillis() / 1000
            val result = LinkedHashMap<String, String>()

            content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val isHttpOnly = line.startsWith(HTTP_ONLY_PREFIX)
                    if (!isHttpOnly && line.startsWith("#")) return@forEach

                    val fields = line.removePrefix(HTTP_ONLY_PREFIX).split('\t')
                    if (fields.size < 7) return@forEach

                    val domain = fields[0].removePrefix(".").lowercase()
                    val domainIsRelevant = domain.endsWith(YOUTUBE_DOMAIN) ||
                        domain.endsWith(GOOGLE_COOKIE_DOMAIN)
                    if (!domainIsRelevant) return@forEach

                    val expires = fields[4].toLongOrNull() ?: 0L
                    // caducidad 0 significa "de sesión": sigue viva.
                    if (expires in 1..now) return@forEach

                    val name = fields[5].trim()
                    val value = fields[6].trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        result[name] = value
                    }
                }

            return result
        }

        /**
         * Construye el valor de la cabecera `Cookie`, o `null` si no hay
         * ninguna cookie de sesión.
         */
        fun buildHeader(cookies: Map<String, String>): String? {
            if (cookies.keys.none { it in SESSION_COOKIES }) return null
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
        private const val YOUTUBE_DOMAIN = "youtube.com"

        /**
         * Los esquemas de autenticación, en el orden en que los acepta YouTube.
         *
         * Cada cookie tiene el suyo: `SAPISID` para la sesión normal y
         * `__Secure-1PAPISID` / `__Secure-3PAPISID` para las de SameSite. Se
         * mandan **todos** los que existan, separados por un espacio, porque
         * YouTube va aceptando cualquiera de ellos según la petición.
         */
        private val SAPISID_SCHEMES = listOf(
            "SAPISIDHASH" to "SAPISID",
            "SAPISID1PHASH" to "__Secure-1PAPISID",
            "SAPISID3PHASH" to "__Secure-3PAPISID"
        )

        /**
         * Construye la cabecera `Authorization` que acredita las cookies.
         *
         * El formato es `<esquema> <unix-segundos>_<sha1(segundos sid origin)>`,
         * con `origin` siendo el origen desde el que se hace la petición. Es lo
         * que acepta la API de Google, y es lo mismo que hace yt-dlp.
         *
         * Se separa [timestampSeconds] para poder probarla en la JVM con una
         * fecha fija: si el valor se calculara dentro, el test solo podría
         * comprobar que no es null.
         */
        fun buildSapisidHashAuthorization(
            cookies: Map<String, String>,
            origin: String,
            timestampSeconds: Long
        ): String? {
            if (cookies.isEmpty()) return null
            val timestamp = timestampSeconds.toString()
            val parts = SAPISID_SCHEMES.mapNotNull { (scheme, cookieName) ->
                val sid = cookies[cookieName]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "$scheme ${timestamp}_${sha1Hex("$timestamp $sid $origin")}"
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun sha1Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val unsigned = byte.toInt() and 0xFF
                hex.append(HEX[unsigned ushr 4])
                hex.append(HEX[unsigned and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
