package com.elimd.downloader.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El contador de "quedan X" es el dato que el usuario mira para decidir si
 * espera o cancela. Si da un numero inventado, es peor que no dar ninguno.
 */
class DownloadEtaTest {

    @Test
    fun `el primer tick no inventa una estimacion`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        val sample = eta.sample(percent = 0f, downloaded = 0L)

        assertEquals(0L, sample.remainingSeconds)
        assertEquals(0L, sample.bytesPerSecond)
    }

    @Test
    fun `estima lo que falta a partir del ritmo medio`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(10_000L)
        // 20% en 10 s: 40 s en total, quedan 80% -> 40 s.
        val sample = eta.sample(percent = 20f, downloaded = 2_000_000L)

        assertEquals(40L, sample.remainingSeconds)
    }

    @Test
    fun `cuenta tambien el multiplexado, no solo los bytes`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(8_000L)
        val sample = eta.sample(percent = 50f, downloaded = 1_000_000L)

        assertEquals(8L, sample.remainingSeconds)
    }

    @Test
    fun `en los primeros segundos no dice nada`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(300L)
        val sample = eta.sample(percent = 5f, downloaded = 50_000L)

        // 5% en 0,3 s daria "quedan 5 s" cuando la descarga no ha durado ni
        // cinco segundos: es ruido de arranque, no informacion.
        assertEquals(0L, sample.remainingSeconds)
    }

    @Test
    fun `la velocidad es la media de bytes por segundo`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(4_000L)
        val sample = eta.sample(percent = 40f, downloaded = 400_000L)

        assertEquals(100_000L, sample.bytesPerSecond)
    }

    @Test
    fun `una reanudacion no cuenta los bytes que ya estaban`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        // El fichero ya tenia 8 MB de una descarga pausada: la velocidad se
        // mide sobre lo que se ha bajado ahora, no sobre el fichero entero.
        eta.sample(percent = 40f, downloaded = 8_000_000L)
        clock.advance(2_000L)
        val sample = eta.sample(percent = 60f, downloaded = 10_000_000L)

        assertEquals(1_000_000L, sample.bytesPerSecond)
        assertEquals(1L, sample.remainingSeconds)
    }

    @Test
    fun `al 100 por ciento no queda nada`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(10_000L)
        val sample = eta.sample(percent = 100f, downloaded = 10_000_000L)

        assertEquals(0L, sample.remainingSeconds)
    }

    @Test
    fun `el cero de la barra no divide por cero`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(10_000L)
        val sample = eta.sample(percent = 0f, downloaded = 0L)

        assertEquals(0L, sample.remainingSeconds)
    }

    @Test
    fun `la estimacion baja a medida que avanza`() {
        val clock = FakeClock()
        val eta = DownloadEta(clock::now)

        eta.sample(percent = 0f, downloaded = 0L)
        clock.advance(10_000L)
        val early = eta.sample(percent = 10f, downloaded = 100_000L).remainingSeconds
        clock.advance(10_000L)
        val late = eta.sample(percent = 80f, downloaded = 800_000L).remainingSeconds

        assertTrue("a los 10% quedaban mas segundos que al 80%", early > late)
    }

    private class FakeClock(private var value: Long = 0L) {
        fun now() = value

        fun advance(millis: Long) {
            value += millis
        }
    }
}
