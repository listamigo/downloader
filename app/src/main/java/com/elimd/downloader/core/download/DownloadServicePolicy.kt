package com.elimd.downloader.core.download

import com.elimd.downloader.data.source.DownloadEngineProgress
import com.elimd.downloader.domain.model.DownloadStatus

/** Intervalo minimo entre refrescos visibles de la notificacion de progreso. */
internal const val NOTIFY_INTERVAL_MS = 200L

/**
 * Accion que [DownloadService] traduce en notificaciones.
 *
 * Separada del servicio a proposito: aqui no hay un solo import de Android,
 * asi que la decision se prueba en la JVM sin emulador ni dobles.
 */
internal sealed interface DownloadServiceAction {
    data class ShowProgress(val percent: Int, val text: String) : DownloadServiceAction
    data class AnnounceCompleted(val fileName: String) : DownloadServiceAction
    data class AnnounceFailed(val fileName: String, val reason: String) : DownloadServiceAction
    object None : DownloadServiceAction
}

/** Si esta en marcha (o en cola): lo que hace que el servicio tenga que vivir. */
internal fun DownloadStatus.isWorkingStatus(): Boolean =
    this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING

/**
 * Decide que hace la notificacion con cada emision del motor.
 *
 * @param progress la emision actual.
 * @param active entradas que el motor sigue llevando (incluidas pausadas).
 * @param previousStatus estado que la notificacion mostro por ultima vez.
 * @param now reloj actual, para poder testear el estrangulamiento.
 * @param lastNotifyAt instante del ultimo refresco visible.
 */
internal fun decideDownloadServiceAction(
    progress: DownloadEngineProgress,
    active: List<DownloadEngineProgress>,
    previousStatus: DownloadStatus?,
    now: Long,
    lastNotifyAt: Long
): DownloadServiceAction {
    val working = active.count { it.status.isWorkingStatus() }

    if (working > 0) {
        // El StateFlow conserva el ultimo valor: con una descarga recien
        // arrancada puede llegar primero el terminal de otra ya cerrada.
        // Anunciarlo seria mentir (la nueva sigue en cola).
        if (!progress.status.isWorkingStatus() && progress.status != DownloadStatus.PAUSED) {
            return DownloadServiceAction.None
        }
        val estrangulado = progress.status == previousStatus &&
            now - lastNotifyAt < NOTIFY_INTERVAL_MS
        if (estrangulado) return DownloadServiceAction.None
        return DownloadServiceAction.ShowProgress(
            percent = progress.progress.toInt().coerceIn(0, 100),
            text = progressLabel(progress, working)
        )
    }

    // Nada activo: solo queda cerrar con el aviso del ultimo resultado, y sin
    // repetirlo si el mismo estado llega dos veces.
    if (progress.status == previousStatus) return DownloadServiceAction.None
    val name = progress.fileName ?: "Descarga"
    return when (progress.status) {
        DownloadStatus.COMPLETED -> DownloadServiceAction.AnnounceCompleted(name)
        DownloadStatus.FAILED ->
            DownloadServiceAction.AnnounceFailed(name, progress.error ?: "Fallo desconocido")
        else -> DownloadServiceAction.None
    }
}

private fun progressLabel(progress: DownloadEngineProgress, working: Int): String = when {
    working > 1 -> "$working descargas en curso"
    progress.fileName != null -> checkNotNull(progress.fileName)
    else -> "Descarga en curso"
}
