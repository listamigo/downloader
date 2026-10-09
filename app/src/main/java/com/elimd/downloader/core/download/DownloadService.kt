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
import com.elimd.downloader.data.source.DownloadEngine
import com.elimd.downloader.data.source.DownloadEngineProgress
import com.elimd.downloader.domain.model.DownloadStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

const val CHANNEL_ID = "download_channel"
const val NOTIFICATION_ID_DOWNLOAD = 1001

/**
 * Foreground Service que mantiene las descargas en background.
 *
 * Lo arranca [DownloadEngineImpl] en cada lanzamiento de trabajo y el propio
 * servicio decide cuando apagarse: mientras haya descargas en cola o en
 * marcha, la notificacion de progreso sigue; cuando el motor no tiene nada
 * activo, anuncia el ultimo resultado y cierra.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var engine: DownloadEngine

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // lifecycleScope solo existe en LifecycleService; con un scope propio el
    // servicio no arrastra dependencias de lifecycle y se cancela en onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var observer: Job? = null
    private var lastStatus: DownloadStatus? = null
    private var lastNotifyAt = 0L
    private var restartedWithoutCaller = false

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
            else -> {
                // Solo el sistema reinicia START_STICKY con intent nulo; el
                // motor siempre manda uno. En una descarga fresca
                // progressFlow puede seguir a null (el primer tick tarda), asi
                // que la unica senal fiable de "proceso muerto" es esto.
                restartedWithoutCaller = intent == null
                // Obligatorio antes de 5 s desde startForegroundService.
                startForegroundSafely(0, "Preparando descarga...")
                observeProgress()
            }
        }
        return START_STICKY
    }

    private fun observeProgress() {
        if (observer?.isActive == true) return
        observer = serviceScope.launch {
            if (restartedWithoutCaller && engine.getActiveDownloads().isEmpty()) {
                // Reinicio del sistema tras matar el proceso: el motor arranca
                // de cero, no hay descargas vivas a las que volver. Ya se ha
                // llamado a startForeground, asi que ahora toca cerrar.
                stopSelfSafely()
                return@launch
            }
            engine.progressFlow.filterNotNull().collect { render(it) }
        }
    }

    private suspend fun render(progress: DownloadEngineProgress) {
        val active = engine.getActiveDownloads()
        val now = System.currentTimeMillis()
        val action = decideDownloadServiceAction(
            progress = progress,
            active = active,
            previousStatus = lastStatus,
            now = now,
            lastNotifyAt = lastNotifyAt
        )

        when (action) {
            is DownloadServiceAction.ShowProgress -> {
                lastStatus = progress.status
                lastNotifyAt = now
                updateProgress(action.percent, action.text)
            }
            is DownloadServiceAction.AnnounceCompleted -> {
                lastStatus = progress.status
                notifyCompleted(action.fileName)
            }
            is DownloadServiceAction.AnnounceFailed -> {
                lastStatus = progress.status
                notifyError(action.fileName, action.reason)
            }
            DownloadServiceAction.None -> Unit
        }

        val working = active.count { it.status.isWorkingStatus() }
        if (working == 0) stopSelfSafely()
    }

    /**
     * Actualiza la notificacion de foreground con el progreso de la descarga.
     */
    private fun updateProgress(progress: Int, text: String) {
        val notification = buildNotification(progress, text)
        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    /**
     * Muestra una notificacion de descarga completada.
     */
    private fun notifyCompleted(fileName: String) {
        postResultNotification(
            title = "Descarga completada",
            text = fileName,
            icon = R.drawable.ic_check
        )
    }

    /**
     * Muestra una notificacion de error.
     */
    private fun notifyError(fileName: String, reason: String) {
        postResultNotification(
            title = "Error en la descarga",
            text = "$fileName: $reason",
            icon = R.drawable.ic_info
        )
    }

    private fun postResultNotification(title: String, text: String, icon: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        // Id distinto al de foreground: cerrar el servicio no debe borrar el
        // aviso de como termino la descarga.
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
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
