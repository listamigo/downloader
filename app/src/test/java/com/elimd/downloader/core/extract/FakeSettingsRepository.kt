package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repositorio de ajustes en memoria para los tests de los resolutores.
 *
 * Solo se usan [getSettings] y [getSettingsFlow]; el resto de metodos existe
 * para satisfacer el contrato y mantener los fakes al dia si el contrato crece.
 */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings()
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override suspend fun saveSettings(settings: AppSettings) {
        state.value = settings
    }

    override suspend fun getSettings(): AppSettings = state.value

    override fun getSettingsFlow(): Flow<AppSettings> = state

    override suspend fun updateTheme(theme: AppTheme) = Unit

    override suspend fun updateLanguage(language: String) = Unit

    override suspend fun updateDownloadLocation(location: String) = Unit

    override suspend fun updateDownloadQuality(quality: String) = Unit

    override suspend fun updateDownloadType(type: DownloadType) = Unit

    override suspend fun updateAutoPlayThumbnails(enabled: Boolean) = Unit

    override suspend fun updateShowNotifications(enabled: Boolean) = Unit

    override suspend fun updateBackgroundAudio(enabled: Boolean) = Unit

    override suspend fun updateMaxConcurrentDownloads(count: Int) = Unit

    override suspend fun updateWallpaperEnabled(enabled: Boolean) = Unit

    override suspend fun updateWallpaperSource(source: String) = Unit

    override suspend fun updateUseRemoteServer(enabled: Boolean) {
        state.value = state.value.copy(useRemoteServer = enabled)
    }

    override suspend fun updateServerUrl(url: String) {
        state.value = state.value.copy(serverUrl = url)
    }

    override suspend fun clearAllSettings() {
        state.value = AppSettings()
    }
}
