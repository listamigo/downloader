package com.elimd.downloader.data.source

import com.elimd.downloader.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Interfaz para la fuente de datos de configuración (DataStore).
 */
interface SettingsDataSource {
    suspend fun saveSettings(settings: AppSettings)
    suspend fun getSettings(): AppSettings
    fun getSettingsFlow(): Flow<AppSettings>
    suspend fun clearSettings()
}