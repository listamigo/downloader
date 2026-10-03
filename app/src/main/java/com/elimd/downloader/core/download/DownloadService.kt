package com.elimd.downloader.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.elimd.downloader.R
import dagger.hilt.android.AndroidEntryPoint

const val CHANNEL_ID = "download_channel"
const val NOTIFICATION_ID_DOWNLOAD = 1001

/**
 * Foreground Service que mantiene las descargas en background.
 * Muestra una notificación con el progreso agregado de las descargas activas.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> startForegroundSafely(0, "Preparando descarga...")
        }
        return START_STICKY
    }

    /**
     * Actualiza la notificacion de foreground con el progreso de la descarga.
     */
    fun updateProgress(progress: Int, fileName: String) {
        if (progress < 0 || progress > 100) return
        val notification = buildNotification(progress, fileName)
        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    /**
     * Muestra una notificacion de descarga completada.
     */
    fun notifyCompleted(fileName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Descarga completada")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        stopSelfSafely()
    }

    /**
     * Muestra una notificacion de error.
     */
    fun notifyError(fileName: String, reason: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle("Error en la descarga")
            .setContentText("$fileName: $reason")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        stopSelfSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD)
        super.onDestroy()
    }

    private fun startForegroundSafely(progress: Int, message: String) {
        val notification = buildNotification(progress, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID_DOWNLOAD, notification)
        }
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(progress: Int, message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("elimd downloader")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress <= 0)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Descargas",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones de descarga de elimd downloader"
            enableLights(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.elimd.downloader.action.STOP_DOWNLOADS"
    }
}
