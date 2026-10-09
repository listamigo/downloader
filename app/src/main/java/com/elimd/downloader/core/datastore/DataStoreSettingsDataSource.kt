package com.elimd.downloader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.data.source.SettingsDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsDataSource(
    private val context: Context
) : SettingsDataSource {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        val DOWNLOAD_TYPE = stringPreferencesKey("download_type")
        val AUTO_PLAY_THUMBNAILS = booleanPreferencesKey("auto_play_thumbnails")
        val SHOW_NOTIFICATIONS = booleanPreferencesKey("show_notifications")
        val BACKGROUND_AUDIO = booleanPreferencesKey("background_audio")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val WALLPAPER_ENABLED = booleanPreferencesKey("wallpaper_enabled")
        val WALLPAPER_SOURCE = stringPreferencesKey("wallpaper_source")
        val USE_REMOTE_SERVER = booleanPreferencesKey("use_remote_server")
        val SERVER_URL = stringPreferencesKey("server_url")
    }

    override suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME] = settings.theme.name
            prefs[Keys.LANGUAGE] = settings.language
            prefs[Keys.DOWNLOAD_LOCATION] = settings.downloadLocation
            prefs[Keys.DOWNLOAD_QUALITY] = settings.downloadQuality
            prefs[Keys.DOWNLOAD_TYPE] = settings.downloadType.name
            prefs[Keys.AUTO_PLAY_THUMBNAILS] = settings.autoPlayThumbnails
            prefs[Keys.SHOW_NOTIFICATIONS] = settings.showNotifications
            prefs[Keys.BACKGROUND_AUDIO] = settings.enableBackgroundAudio
            prefs[Keys.MAX_CONCURRENT] = settings.maxConcurrentDownloads
            prefs[Keys.WALLPAPER_ENABLED] = settings.wallpaperEnabled
            prefs[Keys.WALLPAPER_SOURCE] = settings.wallpaperSource
            prefs[Keys.USE_REMOTE_SERVER] = settings.useRemoteServer
            prefs[Keys.SERVER_URL] = settings.serverUrl
        }
    }

    override suspend fun getSettings(): AppSettings = getSettingsFlow().first()

    override fun getSettingsFlow(): Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                theme = AppTheme.valueOf(prefs[Keys.THEME] ?: AppTheme.SYSTEM.name),
                language = prefs[Keys.LANGUAGE] ?: "system",
                downloadLocation = prefs[Keys.DOWNLOAD_LOCATION] ?: "default",
                downloadQuality = prefs[Keys.DOWNLOAD_QUALITY] ?: "best",
                downloadType = DownloadType.valueOf(prefs[Keys.DOWNLOAD_TYPE] ?: DownloadType.BOTH.name),
                autoPlayThumbnails = prefs[Keys.AUTO_PLAY_THUMBNAILS] ?: true,
                showNotifications = prefs[Keys.SHOW_NOTIFICATIONS] ?: true,
                enableBackgroundAudio = prefs[Keys.BACKGROUND_AUDIO] ?: true,
                maxConcurrentDownloads = prefs[Keys.MAX_CONCURRENT] ?: 3,
                wallpaperEnabled = prefs[Keys.WALLPAPER_ENABLED] ?: false,
                wallpaperSource = prefs[Keys.WALLPAPER_SOURCE] ?: "downloads",
                useRemoteServer = prefs[Keys.USE_REMOTE_SERVER] ?: false,
                serverUrl = prefs[Keys.SERVER_URL] ?: ""
            )
        }

    override suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}