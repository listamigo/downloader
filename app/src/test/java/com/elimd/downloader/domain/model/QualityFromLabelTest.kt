package com.elimd.downloader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reintentar una descarga reconstruye la calidad a partir de la etiqueta que
 * quedo guardada. Si se parsea mal, el reintento baja otra resolucion y el
 * usuario recibe algo que no ha pedido.
 */
class QualityFromLabelTest {

    @Test
    fun `una resolucion simple conserva la altura`() {
        val quality = qualityFromLabel("720p")

        assertEquals(720, quality.height)
        assertNull("sin sufijo no hay fps que respetar", quality.fps)
    }

    @Test
    fun `una resolucion con fps los conserva`() {
        val quality = qualityFromLabel("1080p60")

        assertEquals(1080, quality.height)
        assertEquals(60, quality.fps)
    }

    @Test
    fun `Mejor no fija altura para no recortar la resolucion`() {
        val quality = qualityFromLabel("Mejor")

        assertNull(quality.height)
        assertNull(quality.fps)
        assertTrue("sin altura, el selector es el generico", quality.isBest)
    }

    @Test
    fun `una etiqueta de audio se marca como pista de audio`() {
        val quality = qualityFromLabel("160 kbps")

        assertTrue(quality.isAudioOnly)
        assertNull(quality.height)
    }

    @Test
    fun `una etiqueta desconocida cae en Mejor`() {
        // Peor que pedir de mas es una descarga que no arranca y se queda en
        // cola para siempre.
        assertTrue(qualityFromLabel("lo que sea").isBest)
        assertTrue(qualityFromLabel("").isBest)
    }
}
