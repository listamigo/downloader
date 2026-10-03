package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo

/**
 * Resolucion de un medio a una o dos URLs descargables.
 *
 * YouTube expone los streams de video y audio por separado para calidades altas,
 * por eso [requiresMuxing] indica si hay que unirlos en un solo archivo.
 */
data class ResolvedMedia(
    val videoUrl: String?,
    val audioUrl: String?,
    val videoMimeType: String,
    val audioMimeType: String?,
    val isAudioOnly: Boolean,

    /**
     * Bitrate del video tal como lo publica el extractor, en kbps.
     *
     * Lo necesita el muxer: si acaba recodificando (que es lo que hace), el
     * encoder se pone ahi, y un 480p de cuatro minutos pasa de 18 MB a 62 MB
     * porque el codificador por defecto no tiene ni idea de lo que venia.
     */
    val videoBitrateKbps: Int = 0
) {
    val requiresMuxing: Boolean
        get() = !isAudioOnly && videoUrl != null && audioUrl != null
}

/**
 * Fuente de metadatos y URLs de streaming.
 *
 * Es la frontera del proyecto con el exterior: permite cambiar de backend
 * (extractor local en el dispositivo, o un servidor remoto con yt-dlp)
 * sin tocar la capa de dominio ni la de presentacion.
 */
interface MediaResolver {

    suspend fun search(query: String, pageToken: String?): SearchResult

    suspend fun getVideoInfo(videoId: String): YouTubeVideo?

    suspend fun getAvailableQualities(videoId: String): List<DownloadQuality>

    suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo>

    /**
     * Resuelve las URLs directas de descarga para la calidad y tipo pedidos.
     */
    suspend fun resolveStream(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): ResolvedMedia
}
