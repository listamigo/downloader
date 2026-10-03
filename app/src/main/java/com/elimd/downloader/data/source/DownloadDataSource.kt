package com.elimd.downloader.data.source

import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaz para la fuente de datos de descargas (Room).
 */
interface DownloadDataSource {
    suspend fun insertDownload(download: Download): Long
    suspend fun updateDownload(download: Download)
    suspend fun updateStatus(id: Long, status: DownloadStatus, progress: Float = 0f, speed: Long = 0L, eta: Long = 0L, downloadedBytes: Long = 0L, totalBytes: Long = 0L, error: String? = null, completedAt: Long? = null)
    suspend fun getDownloadById(id: Long): Download?
    suspend fun deleteDownload(id: Long)
    suspend fun deleteAllDownloads()
    fun getAllDownloads(): Flow<List<Download>>
    fun getActiveDownloads(): Flow<List<Download>>
    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>>
    suspend fun getCompletedDownloads(): List<Download>
    suspend fun getPendingDownloads(): List<Download>
    suspend fun getActiveDownloadIds(): List<Long>
}

/**
 * Contrato del motor de descarga.
 *
 * La implementación real es [com.elimd.downloader.core.download.DownloadEngineImpl],
 * que transfiere bytes directamente con OkHttp desde el dispositivo. No requiere
 * ningun binario externo: yt-dlp no puede ejecutarse en Android (ver ADR-005).
 */
interface DownloadEngine {
    /**
     * Progreso de todas las descargas activas. Se correlaciona con la clave
     * primaria de la descarga mediante [DownloadEngineProgress.downloadId].
     */
    val progressFlow: StateFlow<DownloadEngineProgress?>

    /**
     * @param downloadId id asignado por el dominio (normalmente la clave
     *        primaria en Room). Se usa para correlacionar el progreso del motor
     *        con la fila persistida.
     */
    suspend fun startDownload(downloadId: Long, videoId: String, url: String, quality: DownloadQuality, type: DownloadType, outputDir: String, fileName: String): DownloadEngineResult
    suspend fun pauseDownload(pid: Long)
    suspend fun resumeDownload(pid: Long)
    suspend fun cancelDownload(pid: Long)
    suspend fun cancelAllDownloads()
    suspend fun getActiveDownloads(): List<DownloadEngineProgress>
    suspend fun isRunning(pid: Long): Boolean
    suspend fun getProgress(pid: Long): DownloadEngineProgress?
    suspend fun cleanup()
}

data class DownloadEngineResult(
    val success: Boolean,
    val pid: Long,
    val filePath: String? = null,
    val error: String? = null
)

data class DownloadEngineProgress(
    val pid: Long,
    val downloadId: Long,
    val progress: Float,
    val speed: Long, // bytes/sec
    val eta: Long, // seconds
    val totalSize: Long,
    val downloaded: Long,
    val status: DownloadStatus
)