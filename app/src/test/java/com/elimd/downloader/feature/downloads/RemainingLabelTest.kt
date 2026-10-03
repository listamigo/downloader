package com.elimd.downloader.feature.downloads

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El texto del contador se lee de un vistazo, asi que sus unidades importan:
 * "quedan 0 s" o "quedan 3600 s" son peores que no mostrar nada.
 */
class RemainingLabelTest {

    @Test
    fun `sin estimacion no se pinta nada`() {
        assertNull(remainingLabel(0L))
        assertNull(remainingLabel(-5L))
    }

    @Test
    fun `segundos mientras cabe en un minuto`() {
        assertEquals("quedan 1s", remainingLabel(1L))
        assertEquals("quedan 45s", remainingLabel(45L))
        assertEquals("quedan 59s", remainingLabel(59L))
    }

    @Test
    fun `minutos a partir de un minuto`() {
        assertEquals("quedan 1 min", remainingLabel(60L))
        assertEquals("quedan 3 min", remainingLabel(215L))
        assertEquals("quedan 59 min", remainingLabel(3599L))
    }

    @Test
    fun `horas con los minutos que sobren`() {
        assertEquals("quedan 1 h 0 min", remainingLabel(3600L))
        assertEquals("quedan 1 h 5 min", remainingLabel(3900L))
        assertEquals("quedan 2 h 30 min", remainingLabel(9000L))
    }
}

class TransferredLabelTest {

    private val spain = Locale("es", "ES")

    @Test
    fun `lo descargado de lo esperado`() {
        assertEquals("11,7 MB de 28,6 MB", transferredLabel(12_300_000L, 30_000_000L, spain))
    }

    @Test
    fun `sin total conocido se muestra solo lo descargado`() {
        assertEquals("11,7 MB", transferredLabel(12_300_000L, 0L, spain))
    }

    @Test
    fun `sin ningun dato no se pinta nada`() {
        assertNull(transferredLabel(0L, 0L, spain))
    }
}
