package com.elimd.downloader.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["video_id"], unique = true)]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "video_id")
    val videoId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "channel_name")
    val channelName: String,
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "quality")
    val quality: String,
    @ColumnInfo(name = "download_type")
    val downloadType: DownloadType,
    @ColumnInfo(name = "status")
    val status: DownloadStatus,
    @ColumnInfo(name = "progress")
    val progress: Float,
    @ColumnInfo(name = "speed")
    val speed: Long,
    @ColumnInfo(name = "eta")
    val eta: Long,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0L,
    @ColumnInfo(name = "error")
    val error: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    /**
     * Actualiza el estado de una descarga.
     *
     * Los bytes a 0 significan "sin dato", igual que un `eta` a 0: una pausa o
     * una cancelacion no deben borrar lo que ya se llevaba descargado. Al
     * completarse, el tamaño del engine es el del fichero ya escrito, y ese es
     * el que se guarda como tamaño final.
     */
    @Query(
        """
        UPDATE downloads SET
            status = :status,
            progress = :progress,
            speed = :speed,
            eta = :eta,
            downloaded_bytes = CASE WHEN :downloadedBytes > 0 THEN :downloadedBytes ELSE downloaded_bytes END,
            total_bytes = CASE WHEN :totalBytes > 0 THEN :totalBytes ELSE total_bytes END,
            file_size = CASE WHEN :status = 'COMPLETED' THEN :totalBytes ELSE file_size END,
            error = :error,
            completed_at = :completedAt
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        progress: Float = 0f,
        speed: Long = 0L,
        eta: Long = 0L,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        error: String? = null,
        completedAt: Long? = null
    )

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadEntity?

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun deleteAllDownloads()

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED') ORDER BY created_at DESC")
    fun getActiveDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY created_at DESC")
    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY created_at DESC")
    suspend fun getCompletedDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'PAUSED') ORDER BY created_at ASC")
    suspend fun getPendingDownloads(): List<DownloadEntity>

    @Query("SELECT id FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED')")
    suspend fun getActiveDownloadIds(): List<Long>
}

@Database(
    entities = [DownloadEntity::class],
    version = 2,
    exportSchema = false
)
@Singleton
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        const val DATABASE_NAME = "elimd_downloader_db"

        /**
         * Version 2: guarda los bytes transferidos y el total esperado, que son
         * los numeros que se muestran junto al porcentaje.
         *
         * Las descargas ya completadas se quedan con `file_size` a cero: el
         * tamaño final se conoce a partir de ahora, y antes vivia solo en el
         * motor, asi que no hay de donde sacarlo sin volver a mirar el fichero.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN downloaded_bytes INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE downloads ADD COLUMN total_bytes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}

fun DownloadEntity.toDomainModel(): Download = Download(
    id = id,
    videoId = videoId,
    title = title,
    channelName = channelName,
    thumbnailUrl = thumbnailUrl,
    url = url,
    fileName = fileName,
    filePath = filePath,
    fileSize = fileSize,
    mimeType = mimeType,
    quality = quality,
    downloadType = downloadType,
    status = status,
    progress = progress,
    speed = speed,
    eta = eta,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    error = error,
    createdAt = createdAt,
    completedAt = completedAt
)

fun Download.toEntity(): DownloadEntity = DownloadEntity(
    id = id,
    videoId = videoId,
    title = title,
    channelName = channelName,
    thumbnailUrl = thumbnailUrl,
    url = url,
    fileName = fileName,
    filePath = filePath,
    fileSize = fileSize,
    mimeType = mimeType,
    quality = quality,
    downloadType = downloadType,
    status = status,
    progress = progress,
    speed = speed,
    eta = eta,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    error = error,
    createdAt = createdAt,
    completedAt = completedAt
)