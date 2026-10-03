package com.elimd.downloader.data.repository

import android.content.Context
import android.os.Environment
import com.elimd.downloader.data.source.DownloadDataSource
import com.elimd.downloader.data.source.DownloadEngine
import com.elimd.downloader.data.source.SettingsDataSource
import com.elimd.downloader.data.source.YouTubeSearchDataSource
import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.domain.model.qualityFromLabel
import com.elimd.downloader.domain.repository.DownloadRepository
import com.elimd.downloader.domain.repository.SettingsRepository
import com.elimd.downloader.domain.repository.YouTubeSearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSearchRepositoryImpl @Inject constructor(
    private val dataSource: YouTubeSearchDataSource
) : YouTubeSearchRepository {
    override suspend fun searchVideos(query: String, pageToken: String?): SearchResult =
        dataSource.searchVideos(query, pageToken)

    override suspend fun getVideoInfo(videoId: String): YouTubeVideo? =
        dataSource.getVideoInfo(videoId)

    override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> =
        dataSource.getAvailableQualities(videoId)

    override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> =
        dataSource.getRelatedVideos(videoId, limit)
}

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val dataSource: DownloadDataSource,
    private val searchDataSource: YouTubeSearchDataSource,
    private val engine: DownloadEngine,
    @ApplicationContext private val context: Context
) : DownloadRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { observeEngineProgress() }
    }

    /**
     * Proyecta el progreso del motor sobre la tabla de descargas.
     *
     * El motor emite cientos de ticks por segundo al final de la descarga.
     * Escribir en Room en cada tick saturaba al consumidor y hacia que se
     * perdiera el evento final, asi que se limita la frecuencia: el progreso se
     * guarda como maximo cada [PROGRESS_WRITE_INTERVAL_MS], pero un cambio de
     * estado terminal (completado, fallido, cancelado) se escribe siempre.
     */
    private suspend fun observeEngineProgress() {
        var lastWriteAt = 0L
        var lastStatus: DownloadStatus? = null

        engine.progressFlow
            .filterNotNull()
            .collect { progress ->
                val now = System.currentTimeMillis()
                val statusChanged = progress.status != lastStatus
                val intervalElapsed = now - lastWriteAt >= PROGRESS_WRITE_INTERVAL_MS

                // Un cambio de estado siempre se escribe; el progreso solo cuando
                // ha pasado el intervalo, para no martillear la base de datos.
                if (!statusChanged && !intervalElapsed) return@collect

                lastWriteAt = now
                lastStatus = progress.status

                dataSource.updateStatus(
                    id = progress.downloadId,
                    status = progress.status,
                    progress = progress.progress,
                    speed = progress.speed,
                    eta = progress.eta,
                    downloadedBytes = progress.downloaded,
                    totalBytes = progress.totalSize,
                    completedAt = if (progress.status == DownloadStatus.COMPLETED) now else null
                )
            }
    }

    override suspend fun startDownload(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): Download {
        val video = searchDataSource.getVideoInfo(videoId)
            ?: throw IllegalArgumentException("No se pudo obtener la informacion del video $videoId")

        val fileName = sanitizeFileName(video.title)
        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            "descargas"
        ).absolutePath

        val record = Download(
            videoId = video.videoId,
            title = video.title,
            channelName = video.channelName,
            thumbnailUrl = video.thumbnailUrl,
            url = video.urlOrId(),
            fileName = fileName,
            filePath = outputDir,
            fileSize = 0L,
            mimeType = mimeFor(type),
            quality = quality.label,
            downloadType = type,
            status = DownloadStatus.QUEUED
        )

        val id = dataSource.insertDownload(record)

        val result = engine.startDownload(
            downloadId = id,
            videoId = video.videoId,
            url = video.urlOrId(),
            quality = quality,
            type = type,
            outputDir = outputDir,
            fileName = fileName
        )

        if (!result.success) {
            dataSource.updateStatus(
                id = id,
                status = DownloadStatus.FAILED,
                error = result.error
            )
        }
        return (dataSource.getDownloadById(id) ?: record.copy(id = id))
    }

    override suspend fun pauseDownload(id: Long) {
        engine.pauseDownload(id)
        dataSource.updateStatus(id = id, status = DownloadStatus.PAUSED)
    }

    override suspend fun resumeDownload(id: Long) {
        engine.resumeDownload(id)
        dataSource.updateStatus(id = id, status = DownloadStatus.QUEUED)
    }

    override suspend fun cancelDownload(id: Long) {
        engine.cancelDownload(id)
        dataSource.updateStatus(id = id, status = DownloadStatus.CANCELLED)
    }

    override suspend fun cancelAllDownloads() {
        engine.cancelAllDownloads()
        dataSource.getActiveDownloadIds().forEach {
            dataSource.updateStatus(id = it, status = DownloadStatus.CANCELLED)
        }
    }

    override fun getActiveDownloads(): Flow<List<Download>> = dataSource.getActiveDownloads()
    override fun getAllDownloads(): Flow<List<Download>> = dataSource.getAllDownloads()
    override suspend fun getDownloadById(id: Long): Download? = dataSource.getDownloadById(id)

    /**
     * Reintenta una descarga fallida o cancelada.
     *
     * No basta con poner la fila en cola: el motor borra su trabajo al llegar a
     * un estado terminal, asi que hay que volver a lanzarlo con la misma calidad
     * que pidio el usuario. Los ficheros parciales se conservan, de modo que
     * `HttpFileDownloader` continua por donde se quedo en vez de empezar de cero.
     */
    override suspend fun retryDownload(id: Long) {
        val previous = dataSource.getDownloadById(id) ?: return

        val result = engine.startDownload(
            downloadId = id,
            videoId = previous.videoId,
            url = previous.url,
            quality = qualityFromLabel(previous.quality),
            type = previous.downloadType,
            outputDir = previous.filePath,
            fileName = previous.fileName
        )

        if (!result.success) {
            dataSource.updateStatus(id = id, status = DownloadStatus.FAILED, error = result.error)
        }
    }

    override suspend fun deleteDownload(id: Long) = dataSource.deleteDownload(id)
    override suspend fun deleteAllDownloads() = dataSource.deleteAllDownloads()
    override suspend fun getCompletedDownloads(): List<Download> = dataSource.getCompletedDownloads()
    override fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>> =
        dataSource.getDownloadsByStatus(status)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataSource: SettingsDataSource
) : SettingsRepository {
    override suspend fun saveSettings(settings: AppSettings) = dataSource.saveSettings(settings)
    override suspend fun getSettings(): AppSettings = dataSource.getSettings()
    override fun getSettingsFlow(): Flow<AppSettings> = dataSource.getSettingsFlow()
    override suspend fun updateTheme(theme: AppTheme) {
        val current = getSettings()
        saveSettings(current.copy(theme = theme))
    }
    override suspend fun updateLanguage(language: String) {
        val current = getSettings()
        saveSettings(current.copy(language = language))
    }
    override suspend fun updateDownloadLocation(location: String) {
        val current = getSettings()
        saveSettings(current.copy(downloadLocation = location))
    }
    override suspend fun updateDownloadQuality(quality: String) {
        val current = getSettings()
        saveSettings(current.copy(downloadQuality = quality))
    }
    override suspend fun updateDownloadType(type: DownloadType) {
        val current = getSettings()
        saveSettings(current.copy(downloadType = type))
    }
    override suspend fun updateAutoPlayThumbnails(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(autoPlayThumbnails = enabled))
    }
    override suspend fun updateShowNotifications(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(showNotifications = enabled))
    }
    override suspend fun updateBackgroundAudio(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(enableBackgroundAudio = enabled))
    }
    override suspend fun updateMaxConcurrentDownloads(count: Int) {
        val current = getSettings()
        saveSettings(current.copy(maxConcurrentDownloads = count))
    }
    override suspend fun updateWallpaperEnabled(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(wallpaperEnabled = enabled))
    }
    override suspend fun updateWallpaperSource(source: String) {
        val current = getSettings()
        saveSettings(current.copy(wallpaperSource = source))
    }
    override suspend fun clearAllSettings() = dataSource.clearSettings()
}
/**
 * Normaliza un titulo de YouTube para usarlo como nombre de fichero seguro.
 *
 * El titulo es entrada no confiable de la red. Sustituimos los separadores y
 * los caracteres no validos, y descartamos los puntos de los extremos: sin eso
 * un video titulado ".." produciria un nombre que resuelve fuera del directorio
 * de descargas (path traversal).
 */
internal fun sanitizeFileName(title: String): String {
    val cleaned = title
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .trim()
    return cleaned.take(80).ifEmpty { "descarga" }
}

/** Un estado terminal no debe perder nunca su escritura en la base de datos. */
private fun DownloadStatus.isTerminal(): Boolean =
    this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED || this == DownloadStatus.CANCELLED

/** Frecuencia maxima de escritura del progreso en Room. */
private const val PROGRESS_WRITE_INTERVAL_MS = 500L

internal fun mimeFor(type: DownloadType): String = when (type) {
    DownloadType.AUDIO -> "audio/mp4"
    DownloadType.VIDEO, DownloadType.BOTH -> "video/mp4"
}

private fun YouTubeVideo.urlOrId(): String =
    "https://www.youtube.com/watch?v=$videoId"
