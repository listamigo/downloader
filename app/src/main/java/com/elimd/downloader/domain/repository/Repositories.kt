package com.elimd.downloader.domain.repository

import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de acceso a datos de busqueda en YouTube.
 */
interface YouTubeSearchRepository {

    suspend fun searchVideos(query: String, pageToken: String?): SearchResult

    suspend fun getVideoInfo(videoId: String): YouTubeVideo?

    suspend fun getAvailableQualities(videoId: String): List<DownloadQuality>

    suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo>
}

/**
 * Contrato de gestion de descargas.
 */
interface DownloadRepository {

    suspend fun startDownload(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): Download

    suspend fun pauseDownload(id: Long)

    suspend fun resumeDownload(id: Long)

    suspend fun cancelDownload(id: Long)

    suspend fun cancelAllDownloads()

    fun getActiveDownloads(): Flow<List<Download>>

    fun getAllDownloads(): Flow<List<Download>>

    suspend fun getDownloadById(id: Long): Download?

    suspend fun deleteDownload(id: Long)

    suspend fun deleteAllDownloads()

    suspend fun retryDownload(id: Long)

    suspend fun getCompletedDownloads(): List<Download>

    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>>
}

/**
 * Contrato de preferencias de la aplicacion.
 */
interface SettingsRepository {

    suspend fun saveSettings(settings: AppSettings)

    suspend fun getSettings(): AppSettings

    fun getSettingsFlow(): Flow<AppSettings>

    suspend fun updateTheme(theme: AppTheme)

    suspend fun updateLanguage(language: String)

    suspend fun updateDownloadLocation(location: String)

    suspend fun updateDownloadQuality(quality: String)

    suspend fun updateDownloadType(type: DownloadType)

    suspend fun updateAutoPlayThumbnails(enabled: Boolean)

    suspend fun updateShowNotifications(enabled: Boolean)

    suspend fun updateBackgroundAudio(enabled: Boolean)

    suspend fun updateMaxConcurrentDownloads(count: Int)

    suspend fun updateWallpaperEnabled(enabled: Boolean)

    suspend fun updateWallpaperSource(source: String)

    suspend fun clearAllSettings()
}
