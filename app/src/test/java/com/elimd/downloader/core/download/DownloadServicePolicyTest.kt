package com.elimd.downloader.core.download

import com.elimd.downloader.data.source.DownloadEngineProgress
import com.elimd.downloader.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La notificacion de foreground service es lo unico que el usuario ve cuando
 * la app no esta en pantalla. Si aqui falla, el servicio se cuelga con la
 * barra abierta o anuncia resultados que no son.
 */
class DownloadServicePolicyTest {

    @Test
    fun `refresca la notificacion cuando llega un tick nuevo`() {
        val action = decide(
            progress = con(DownloadStatus.DOWNLOADING, percent = 45f),
            active = listOf(con(DownloadStatus.DOWNLOADING, percent = 45f)),
            previousStatus = DownloadStatus.QUEUED
        )

        val show = action as DownloadServiceAction.ShowProgress
        assertEquals(45, show.percent)
        assertEquals("video", show.text)
    }

    @Test
    fun `estrangula los ticks del mismo estado dentro del intervalo`() {
        val progress = con(DownloadStatus.DOWNLOADING, percent = 50f)
        val active = listOf(progress)

        val primero = decide(progress, active, DownloadStatus.DOWNLOADING, now = 1_000L, lastNotifyAt = 0L)
        val muyPronto = decide(progress, active, DownloadStatus.DOWNLOADING, now = 1_199L, lastNotifyAt = 1_000L)
        val yaTocado = decide(progress, active, DownloadStatus.DOWNLOADING, now = 1_200L, lastNotifyAt = 1_000L)

        assertTrue(primero is DownloadServiceAction.ShowProgress)
        assertEquals(DownloadServiceAction.None, muyPronto)
        assertTrue(yaTocado is DownloadServiceAction.ShowProgress)
    }

    @Test
    fun `no toca la notificacion con el terminal estancado de otra descarga`() {
        // El StateFlow conserva el valor: la recien arrancada aun no ha emitido
        // y lo primero que llega puede ser la completada de la anterior.
        val action = decide(
            progress = con(DownloadStatus.COMPLETED, percent = 100f),
            active = listOf(con(DownloadStatus.QUEUED)),
            previousStatus = DownloadStatus.DOWNLOADING
        )

        assertEquals(DownloadServiceAction.None, action)
    }

    @Test
    fun `anuncia la completada cuando ya no queda nada activo`() {
        val action = decide(
            progress = con(DownloadStatus.COMPLETED, percent = 100f, fileName = "mi video"),
            active = emptyList(),
            previousStatus = DownloadStatus.DOWNLOADING
        )

        assertEquals(
            DownloadServiceAction.AnnounceCompleted("mi video"),
            action
        )
    }

    @Test
    fun `anuncia el fallo con el motivo que informo el motor`() {
        val action = decide(
            progress = con(DownloadStatus.FAILED, error = "sin red"),
            active = emptyList(),
            previousStatus = DownloadStatus.DOWNLOADING
        )

        assertEquals(
            DownloadServiceAction.AnnounceFailed("video", "sin red"),
            action
        )
    }

    @Test
    fun `si el motor no informo del motivo se usa el generico`() {
        val action = decide(
            progress = con(DownloadStatus.FAILED),
            active = emptyList(),
            previousStatus = DownloadStatus.DOWNLOADING
        )

        assertEquals(
            DownloadServiceAction.AnnounceFailed("video", "Fallo desconocido"),
            action
        )
    }

    @Test
    fun `no repite el anuncio si el estado no cambia`() {
        val action = decide(
            progress = con(DownloadStatus.COMPLETED, percent = 100f),
            active = emptyList(),
            previousStatus = DownloadStatus.COMPLETED
        )

        assertEquals(DownloadServiceAction.None, action)
    }

    @Test
    fun `con varias en marcha el texto habla del conjunto`() {
        val action = decide(
            progress = con(DownloadStatus.DOWNLOADING, percent = 10f),
            active = listOf(
                con(DownloadStatus.DOWNLOADING, percent = 10f),
                con(DownloadStatus.DOWNLOADING, percent = 20f)
            ),
            previousStatus = DownloadStatus.QUEUED
        )

        assertEquals("2 descargas en curso", (action as DownloadServiceAction.ShowProgress).text)
    }

    @Test
    fun `el porcentaje no se sale de la barra`() {
        val fueraDeRango = decide(
            progress = con(DownloadStatus.DOWNLOADING, percent = 150f),
            active = listOf(con(DownloadStatus.DOWNLOADING, percent = 150f)),
            previousStatus = DownloadStatus.QUEUED
        )
        val negativo = decide(
            progress = con(DownloadStatus.DOWNLOADING, percent = -5f),
            active = listOf(con(DownloadStatus.DOWNLOADING, percent = -5f)),
            previousStatus = DownloadStatus.QUEUED
        )

        assertEquals(100, (fueraDeRango as DownloadServiceAction.ShowProgress).percent)
        assertEquals(0, (negativo as DownloadServiceAction.ShowProgress).percent)
    }

    @Test
    fun `con todo en pausa no anuncia nada y el servicio se cierra fuera`() {
        val action = decide(
            progress = con(DownloadStatus.PAUSED),
            active = listOf(con(DownloadStatus.PAUSED)),
            previousStatus = DownloadStatus.DOWNLOADING
        )

        assertEquals(DownloadServiceAction.None, action)
    }

    private fun decide(
        progress: DownloadEngineProgress,
        active: List<DownloadEngineProgress>,
        previousStatus: DownloadStatus?,
        now: Long = 0L,
        lastNotifyAt: Long = 0L
    ) = decideDownloadServiceAction(progress, active, previousStatus, now, lastNotifyAt)

    private fun con(
        status: DownloadStatus,
        percent: Float = 0f,
        fileName: String? = "video",
        error: String? = null
    ) = DownloadEngineProgress(
        pid = 1L,
        downloadId = 1L,
        progress = percent,
        speed = 0L,
        eta = 0L,
        totalSize = 0L,
        downloaded = 0L,
        status = status,
        fileName = fileName,
        error = error
    )
}
