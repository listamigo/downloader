package com.elimd.downloader.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aqui no hay red ni ficheros: solo el reparto de bytes. Un error de calculo no
 * da una excepcion, da un fichero con huecos que se ve al final, asi que estos
 * tests son la unica red de seguridad.
 */
class SegmentPlannerTest {

    @Test
    fun `sin nada descargado falta todo`() {
        assertEquals(listOf(Range(0, 99)), missingRanges(emptyList(), total = 100))
    }

    @Test
    fun `lo descargado hasta la mitad deja la otra mitad`() {
        assertEquals(listOf(Range(50, 99)), missingRanges(listOf(Range(0, 49)), total = 100))
    }

    @Test
    fun `con todo descargado no falta nada`() {
        assertEquals(emptyList<Range>(), missingRanges(listOf(Range(0, 99)), total = 100))
    }

    @Test
    fun `los huecos del principio y del final se respetan`() {
        val missing = missingRanges(listOf(Range(20, 49), Range(80, 99)), total = 100)

        assertEquals(listOf(Range(0, 19), Range(50, 79)), missing)
    }

    @Test
    fun `un hueco se cuenta una vez aunque este partido en trozos`() {
        // Dos segmentos que se solapan no pueden dejar un hueco fantasma en el
        // medio: se cuenta lo que ya hay, no lo que se pidio dos veces.
        val missing = missingRanges(listOf(Range(0, 30), Range(20, 60)), total = 100)

        assertEquals(listOf(Range(61, 99)), missing)
    }

    @Test
    fun `lo que se sale del total no cuenta`() {
        val missing = missingRanges(listOf(Range(0, 150)), total = 100)

        assertEquals(emptyList<Range>(), missing)
    }

    @Test
    fun `un total cero o negativo no rompe nada`() {
        assertEquals(emptyList<Range>(), missingRanges(emptyList(), total = 0))
        assertEquals(emptyList<Range>(), missingRanges(listOf(Range(0, 10)), total = -5))
    }

    // ---------------- reparto ----------------

    @Test
    fun `un fichero grande se parte en los trozos pedidos`() {
        val segments = planSegments(listOf(Range(0, 9_999_999)), maxSegments = 4, minSegmentBytes = 1_000_000)

        assertEquals(4, segments.size)
        assertEquals(2_500_000L, segments.first().size)
        // Sin huecos ni solapes: 0-2499999, 2500000-4999999...
        segments.zipWithNext { previous, next -> assertEquals(previous.endExclusive, next.start) }
        assertEquals(0L, segments.first().start)
        assertEquals(9_999_999L, segments.last().endInclusive)
    }

    @Test
    fun `un fichero pequeno no se parte`() {
        // Cuatro peticiones para 300 KB sale mas caro que el ahorro.
        val segments = planSegments(listOf(Range(0, 299_999)), maxSegments = 4, minSegmentBytes = 1_000_000)

        assertEquals(listOf(Range(0, 299_999)), segments)
    }

    @Test
    fun `el presupuesto se gasta en el primer hueco`() {
        val segments = planSegments(
            missing = listOf(Range(0, 299_999), Range(300_000, 20_299_999)),
            maxSegments = 4,
            minSegmentBytes = 1_000_000
        )

        // El hueco pequeño no se parte (1 trozo) y se lleva el presupuesto, asi
        // que el grande se queda con uno por debajo del maximo... y el total
        // nunca pasa de 4.
        assertEquals(4, segments.size)
        assertEquals(Range(0, 299_999), segments.first())
        assertTrue("no se pasa del maximo de trozos", segments.size <= 4)
    }

    @Test
    fun `mas huecos que presupuesto da al menos un trozo a cada uno`() {
        // El primer hueco se lleva el presupuesto entero; los otros dos, uno cada
        // uno, porque un hueco no se puede dejar sin bajar.
        val segments = planSegments(
            missing = listOf(Range(0, 5_000_000), Range(6_000_000, 11_000_000), Range(12_000_000, 17_000_000)),
            maxSegments = 2,
            minSegmentBytes = 1_000_000
        )

        assertEquals(4, segments.size)
        assertTrue(segments.zipWithNext().all { (a, b) -> a.endExclusive <= b.start })
        assertEquals(17_000_000L, segments.last().endInclusive)
    }

    @Test
    fun `nada que repartir devuelve nada`() {
        assertEquals(emptyList<Range>(), planSegments(emptyList(), maxSegments = 4, minSegmentBytes = 1_000))
        assertEquals(emptyList<Range>(), planSegments(listOf(Range(0, 10)), maxSegments = 0, minSegmentBytes = 1_000))
    }

    // ---------------- registro de lo completado ----------------

    @Test
    fun `el registro sobrevive a una vuelta de texto`() {
        val ranges = listOf(Range(0, 999), Range(2000, 2999))

        assertEquals(ranges, parseRanges(serializeRanges(ranges)))
    }

    @Test
    fun `un registro a medias se ignora en vez de romper`() {
        // Si el fichero se corto a media escritura, ese trozo se vuelve a bajar.
        assertEquals(listOf(Range(0, 999)), parseRanges("0-999\nbasura\n"))
        assertEquals(emptyList<Range>(), parseRanges(""))
    }

    @Test
    fun `un rango invertido se descarta`() {
        assertEquals(emptyList<Range>(), parseRanges("500-100\n"))
    }

    // ---------------- la combinacion que importa ----------------

    @Test
    fun `reanudar tras una pausa no vuelve a pagar ni un byte`() {
        // Se pausó con dos segmentos de 4 MB hechos de un fichero de 20 MB. Los
        // trozos tienen que cubrir exactamente lo que faltaba: ni uno de mas (se
        // pagaria dos veces) ni uno de menos (quedaria un hueco).
        val completed = listOf(Range(0, 3_999_999), Range(8_000_000, 11_999_999))

        val missing = missingRanges(completed, total = 20_000_000)
        val segments = planSegments(missing, maxSegments = 4, minSegmentBytes = 1_000_000)

        assertEquals(listOf(Range(4_000_000, 7_999_999), Range(12_000_000, 19_999_999)), missing)
        assertEquals("los trozos no se solapan", emptyList<Pair<Long, Long>>(), segments.overlaps())
        assertEquals("nada de lo ya descargado se vuelve a pedir", 4_000_000L, segments.first().start)
        assertEquals(19_999_999L, segments.last().endInclusive)
        assertEquals("no se repite ningun byte", 12_000_000L, segments.sumOf { it.size })
    }

    private fun List<Range>.overlaps(): List<Pair<Long, Long>> =
        zipWithNext().filter { (a, b) -> b.start < a.endExclusive }.map { (a, b) -> a.start to b.start }
}