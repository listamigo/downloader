package com.elimd.downloader.data.source

import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo

/**
 * Interfaz para la fuente de búsquedas de YouTube.
 *
 * La implementación real es [com.elimd.downloader.core.network.YouTubeSearchDataSourceImpl],
 * que delega en el MediaResolver: hoy NewPipeExtractor sobre el dispositivo, y
 * opcionalmente un servidor propio con yt-dlp (plan B).
 */
interface YouTubeSearchDataSource {
    suspend fun searchVideos(query: String, pageToken: String? = null): SearchResult
    suspend fun getVideoInfo(videoId: String): YouTubeVideo?
    suspend fun getAvailableQualities(videoId: String): List<DownloadQuality>
    suspend fun getRelatedVideos(videoId: String, limit: Int = 10): List<YouTubeVideo>
    suspend fun downloadThumbnail(videoId: String, quality: String = "maxres"): String?
    suspend fun getVideoFormats(videoId: String): List<DownloadQuality>
}