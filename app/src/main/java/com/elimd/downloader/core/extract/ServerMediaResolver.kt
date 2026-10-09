package com.elimd.downloader.core.extract

import com.elimd.downloader.core.di.DefaultServerUrl
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.domain.repository.SettingsRepository
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Plan B (ADR-022): resuelve metadatos y URLs de descarga contra el backend
 * propio con yt-dlp.
 *
 * El servidor entrega un unico archivo ya muxeado (MP4 para video, M4A para
 * audio), asi que [resolveStream] devuelve una sola URL y [ResolvedMedia]
 * nunca pide unir pistas: el trabajo pesado lo hace el servidor y el cliente
 * solo baja bytes con Range.
 *
 * La URL base se lee de los ajustes en cada llamada: si el usuario la cambia,
 * la siguiente operacion ya usa la nueva sin reiniciar.
 */
class ServerMediaResolver @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    @DefaultServerUrl private val builtInServerUrl: String
) : MediaResolver {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, pageToken: String?): SearchResult =
        withContext(Dispatchers.IO) {
            // El token de la app local es un numero de pagina; el del servidor
            // tambien. Si no es un entero se empieza por la primera pagina.
            val page = pageToken?.toIntOrNull() ?: 0
            val body = get("${baseUrl()}/api/search?q=${encode(query)}&page=$page")
            val dto = json.decodeFromString<ServerSearchDto>(body)
            SearchResult(
                query = dto.query.ifEmpty { query },
                videos = dto.videos.map { it.toDomain() },
                nextPageToken = dto.nextPage,
                hasNextPage = dto.hasNextPage
            )
        }

    override suspend fun getVideoInfo(videoId: String): YouTubeVideo? =
        withContext(Dispatchers.IO) {
            val body = get("${baseUrl()}/api/video/${encode(videoId)}")
            json.decodeFromString<ServerVideoDto>(body).toDomain()
        }

    override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> =
        withContext(Dispatchers.IO) {
            val body = get("${baseUrl()}/api/qualities/${encode(videoId)}")
            json.decodeFromString<List<ServerQualityDto>>(body).map { it.toDomain() }
        }

    override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> {
        // El servidor no expone relacionados (yt-dlp no los da). SwitchingMediaResolver
        // siempre enruta esta llamada al extractor local; llegar aqui es un error
        // de programacion, no una condicion que se deba tapar con una lista vacia.
        throw UnsupportedOperationException(
            "El servidor remoto no ofrece videos relacionados; usalos desde el resolver local"
        )
    }

    override suspend fun resolveStream(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val kind = if (type == DownloadType.AUDIO) "audio" else "video"
        val mediaUrl =
            "${baseUrl()}/api/media/${encode(videoId)}?quality=${encode(quality.id)}&type=$kind"
        if (kind == "audio") {
            ResolvedMedia(
                videoUrl = null,
                audioUrl = mediaUrl,
                videoMimeType = "",
                audioMimeType = "audio/mp4",
                isAudioOnly = true
            )
        } else {
            // Un solo MP4 con video y audio ya unidos: audioUrl null hace que
            // DownloadEngine baje el archivo directamente, sin muxear de nuevo.
            ResolvedMedia(
                videoUrl = mediaUrl,
                audioUrl = null,
                videoMimeType = "video/mp4",
                audioMimeType = null,
                isAudioOnly = false
            )
        }
    }

    /**
     * La URL de fabricacion (inyectada via [com.elimd.downloader.core.di.DefaultServerUrl])
     * actua como valor por defecto: activar el interruptor basta para usar el
     * servidor desplegado sin escribir nada. Una URL guardada en ajustes gana.
     */
    private suspend fun baseUrl(): String {
        val saved = settingsRepository.getSettings().serverUrl.trim().trimEnd('/')
        val url = if (saved.isEmpty()) {
            builtInServerUrl.trim().trimEnd('/')
        } else {
            saved
        }
        if (url.isEmpty()) {
            throw ServerNotConfiguredException(
                "El modo servidor esta activado pero no hay URL configurada"
            )
        }
        return url
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ServerException(
                    "El servidor respondio ${response.code} en $url" +
                        if (body.isBlank()) "" else ": ${body.take(300)}"
                )
            }
            return body
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** El modo servidor esta activo pero falta la URL, o no es utilizable. */
class ServerNotConfiguredException(message: String) : IllegalStateException(message)

/** El servidor respondio con un error HTTP o una respuesta inesperada. */
class ServerException(message: String) : RuntimeException(message)

@Serializable
internal data class ServerVideoDto(
    val videoId: String,
    val title: String = "",
    val channelName: String = "",
    val duration: String = "",
    val viewCount: String = "",
    val uploadDate: String = "",
    val thumbnailUrl: String = "",
    val isLive: Boolean = false
) {
    fun toDomain() = YouTubeVideo(
        videoId = videoId,
        title = title,
        channelName = channelName,
        duration = duration,
        viewCount = viewCount,
        uploadDate = uploadDate,
        thumbnailUrl = thumbnailUrl,
        isLive = isLive
    )
}

@Serializable
internal data class ServerSearchDto(
    val query: String = "",
    val videos: List<ServerVideoDto> = emptyList(),
    val nextPage: String? = null,
    val hasNextPage: Boolean = false
)

@Serializable
internal data class ServerQualityDto(
    val id: String,
    val label: String = "",
    val format: String = "mp4",
    val height: Int? = null,
    val fps: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    val isAudioOnly: Boolean = false
) {
    fun toDomain() = DownloadQuality(
        id = id,
        label = label,
        format = format,
        height = height,
        fps = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        fileSize = fileSize,
        isAudioOnly = isAudioOnly
    )
}
