package com.elimd.downloader.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Que el muxer convierta o solo empaquete decide el peso del archivo final: un
 * 480p de cuatro minutos son 18 MB si se copia y 62 MB si se recodifica.
 */
class MuxerFormatTest {

    @Test
    fun `h264 con aac se empaqueta tal cual`() {
        assertFalse(needsMuxConversion(videoMimeType = "video/mp4", audioMimeType = "audio/mp4"))
    }

    @Test
    fun `video suelto sin audio tambien se empaqueta`() {
        assertFalse(needsMuxConversion(videoMimeType = "video/mp4", audioMimeType = null))
    }

    @Test
    fun `un vp9 hay que convertirlo a h264`() {
        assertTrue(needsMuxConversion(videoMimeType = "video/webm", audioMimeType = "audio/mp4"))
    }

    @Test
    fun `un opus hay que convertirlo a aac`() {
        assertTrue(needsMuxConversion(videoMimeType = "video/mp4", audioMimeType = "audio/webm"))
        assertTrue(needsMuxConversion(videoMimeType = "video/mp4", audioMimeType = "audio/ogg"))
    }

    @Test
    fun `un bitrate ausente no se inventa`() {
        assertNull(sourceBitrateOrNull(0))
        assertNull(sourceBitrateOrNull(-1))
    }

    @Test
    fun `el bitrate del extractor viene en kbps`() {
        assertEquals(450_000, sourceBitrateOrNull(450))
    }

    @Test
    fun `un bitrate absurdo se descarta en vez de colarse`() {
        // Un stream que venga ya en bps (450000) o en silencio no puede pasar tal
        // cual al encoder.
        assertNull(sourceBitrateOrNull(450_000))
        assertNull(sourceBitrateOrNull(99_999))
    }
}

/**
 * Que pista entra por el camino de copiar y cual por el de convertir.
 *
 * La distincion es lo que decide el peso del archivo que se le da al usuario, y
 * por eso vive en una funcion pura sin Android que se pueda probar en la JVM.
 */
class RemuxDecisionTest {

    @Test
    fun `el caso del resolutor se copia`() {
        // Lo que produce `NewPipeMediaResolver.mimeOf` para un H.264 y un m4a:
        // es la descarga real de la app, y tiene que ir por el camino barato.
        assertTrue(canRemuxWithoutReencoding(videoMimeType = "video/mp4", audioMimeType = "audio/mp4"))
    }

    @Test
    fun `video suelto sin audio se copia`() {
        assertTrue(canRemuxWithoutReencoding(videoMimeType = "video/mp4", audioMimeType = null))
    }

    @Test
    fun `un video webm no cabe en un mp4`() {
        // El MP4 admite H.264 y H.265, no VP9: hay que convertirlo.
        assertFalse(canRemuxWithoutReencoding(videoMimeType = "video/webm", audioMimeType = "audio/mp4"))
    }

    @Test
    fun `un audio opus llega en webm`() {
        assertFalse(canRemuxWithoutReencoding(videoMimeType = "video/mp4", audioMimeType = "audio/webm"))
        assertFalse(canRemuxWithoutReencoding(videoMimeType = "video/mp4", audioMimeType = "audio/ogg"))
    }

    @Test
    fun `los dos caminos son el contrario el uno del otro`() {
        // Si se pueden copiar y hay que convertir a la vez, las dos funciones
        // darian decisiones contradictorias, que es como se cuelan los bugs.
        val casos = listOf(
            "video/mp4" to "audio/mp4",
            "video/mp4" to null,
            "video/mp4" to "audio/ogg",
            "video/webm" to "audio/mp4",
            "video/webm" to "audio/webm"
        )
        casos.forEach { (video, audio) ->
            assertEquals(
                "video=$video audio=$audio",
                !canRemuxWithoutReencoding(video, audio),
                needsMuxConversion(video, audio)
            )
        }
    }
}

class MuxerBitrateTest {

    @Test
    fun `el bitrate medido sale de los bytes y la duracion`() {
        // 1.000 bytes en 20 ms son 400 kbps. Es el calculo del 480p de YouTube
        // (13.888.258 bytes en 248,9 s = 446 kbps) reducido de escala, para que
        // el test no tenga que escribir 13 MB en un temporal.
        val file = tempVideo(1_000L)

        assertEquals(400_000, measuredBitrate(file, durationMs = 20L))
    }

    @Test
    fun `sin duracion no hay nada que estimar`() {
        assertNull(measuredBitrate(tempVideo(1_000L), durationMs = null))
        assertNull(measuredBitrate(tempVideo(1_000L), durationMs = 0L))
    }

    @Test
    fun `un fichero vacio no produce un bitrate`() {
        assertNull(measuredBitrate(tempVideo(0L), durationMs = 20L))
    }

    @Test
    fun `un bitrate medido absurdo se descarta`() {
        // 1.000 bytes en 1 ms son 8 Mbps... pero 1 byte en 1 ms son 8 kbps, que no
        // es un video: mejor no tocar el encoder que darle un numero sin sentido.
        assertNull(measuredBitrate(tempVideo(1L), durationMs = 1L))
    }

    @Test
    fun `lo declarado por el extractor tiene prioridad sobre lo medido`() {
        // Si YouTube dice el bitrate, se cree: es mas exacto que dividir.
        assertEquals(900_000, sourceBitrateOrNull(900))
    }

    private fun tempVideo(bytes: Long): File =
        File.createTempFile("muxer-bitrate", ".mp4").apply {
            deleteOnExit()
            writeBytes(ByteArray(bytes.toInt()))
        }
}
