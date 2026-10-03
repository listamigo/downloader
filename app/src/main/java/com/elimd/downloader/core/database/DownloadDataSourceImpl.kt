package com.elimd.downloader.core.database

import com.elimd.downloader.data.source.DownloadDataSource
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DownloadDataSourceImpl(
    private val appDatabase: AppDatabase
) : DownloadDataSource {

    private val dao = appDatabase.downloadDao()

    override suspend fun insertDownload(download: Download): Long =
        dao.insert(download.toEntity())

    override suspend fun updateDownload(download: Download) =
        dao.update(download.toEntity())

    override suspend fun updateStatus(id: Long, status: DownloadStatus, progress: Float, speed: Long, eta: Long, downloadedBytes: Long, totalBytes: Long, error: String?, completedAt: Long?) =
        dao.updateStatus(id, status, progress, speed, eta, downloadedBytes, totalBytes, error, completedAt)

    override suspend fun getDownloadById(id: Long): Download? =
        dao.getDownloadById(id)?.toDomainModel()

    override suspend fun deleteDownload(id: Long) = dao.deleteDownload(id)

    override suspend fun deleteAllDownloads() = dao.deleteAllDownloads()

    override fun getAllDownloads(): Flow<List<Download>> =
        dao.getAllDownloads().map { list -> list.map { it.toDomainModel() } }

    override fun getActiveDownloads(): Flow<List<Download>> =
        dao.getActiveDownloads().map { list -> list.map { it.toDomainModel() } }

    override fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>> =
        dao.getDownloadsByStatus(status).map { list -> list.map { it.toDomainModel() } }

    override suspend fun getCompletedDownloads(): List<Download> =
        dao.getCompletedDownloads().map { it.toDomainModel() }

    override suspend fun getPendingDownloads(): List<Download> =
        dao.getPendingDownloads().map { it.toDomainModel() }

    override suspend fun getActiveDownloadIds(): List<Long> =
        dao.getActiveDownloadIds()
}