package com.elimd.downloader.domain.usecase

import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.domain.repository.DownloadRepository
import com.elimd.downloader.domain.repository.SettingsRepository
import com.elimd.downloader.domain.repository.YouTubeSearchRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// YouTube Search Use Cases
// ==========================

class SearchYouTubeUseCase @Inject constructor(
    private val repository: YouTubeSearchRepository
) {
    suspend operator fun invoke(query: String, pageToken: String? = null): SearchResult =
        repository.searchVideos(query, pageToken)
}

class GetVideoInfoUseCase @Inject constructor(
    private val repository: YouTubeSearchRepository
) {
    suspend operator fun invoke(videoId: String): YouTubeVideo? =
        repository.getVideoInfo(videoId)
}

class GetQualitiesUseCase @Inject constructor(
    private val repository: YouTubeSearchRepository
) {
    suspend operator fun invoke(videoId: String): List<DownloadQuality> =
        repository.getAvailableQualities(videoId)
}

class GetRelatedVideosUseCase @Inject constructor(
    private val repository: YouTubeSearchRepository
) {
    suspend operator fun invoke(videoId: String, limit: Int = 10): List<YouTubeVideo> =
        repository.getRelatedVideos(videoId, limit)
}

// Download Use Cases
// ==================

class StartDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(videoId: String, quality: DownloadQuality, type: DownloadType): Download =
        repository.startDownload(videoId, quality, type)
}

class PauseDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.pauseDownload(id)
}

class ResumeDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.resumeDownload(id)
}

class CancelDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.cancelDownload(id)
}

class CancelAllDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke() = repository.cancelAllDownloads()
}

class GetActiveDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke(): Flow<List<Download>> = repository.getActiveDownloads()
}

class GetAllDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke(): Flow<List<Download>> = repository.getAllDownloads()
}

class GetDownloadByIdUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long): Download? = repository.getDownloadById(id)
}

class DeleteDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.deleteDownload(id)
}

class DeleteAllDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke() = repository.deleteAllDownloads()
}

class RetryDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.retryDownload(id)
}

class GetCompletedDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(): List<Download> = repository.getCompletedDownloads()
}

class GetDownloadsByStatusUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke(status: DownloadStatus): Flow<List<Download>> =
        repository.getDownloadsByStatus(status)
}

// Settings Use Cases
// ==================

class GetSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(): AppSettings = repository.getSettings()
}

class SaveSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(settings: AppSettings) = repository.saveSettings(settings)
}

class GetSettingsFlowUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> = repository.getSettingsFlow()
}

class UpdateThemeUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(theme: AppTheme) = repository.updateTheme(theme)
}

class UpdateLanguageUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(language: String) = repository.updateLanguage(language)
}

class UpdateDownloadLocationUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(location: String) = repository.updateDownloadLocation(location)
}

class UpdateDownloadQualityUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(quality: String) = repository.updateDownloadQuality(quality)
}

class UpdateDownloadTypeUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(type: DownloadType) = repository.updateDownloadType(type)
}

class UpdateAutoPlayThumbnailsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.updateAutoPlayThumbnails(enabled)
}

class UpdateShowNotificationsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.updateShowNotifications(enabled)
}

class UpdateBackgroundAudioUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.updateBackgroundAudio(enabled)
}

class UpdateMaxConcurrentDownloadsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(count: Int) = repository.updateMaxConcurrentDownloads(count)
}

class UpdateWallpaperEnabledUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(enabled: Boolean) = repository.updateWallpaperEnabled(enabled)
}

class UpdateWallpaperSourceUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(source: String) = repository.updateWallpaperSource(source)
}

class ClearAllSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke() = repository.clearAllSettings()
}