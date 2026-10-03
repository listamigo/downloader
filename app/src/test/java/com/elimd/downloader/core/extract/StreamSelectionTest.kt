package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La eleccion de calidad es la decision que hace fallar la app cuando se
 * equivoca: si el usuario pide 1080p y se le da un 360p silenciosamente, o si
 * una descarga de audio acaba con un MP4 de video.
 *
 * Aqui no hay red ni extractor: solo la politica de [StreamSelection].
 */
class StreamSelectionTest {

    // ---------------- pickFor ----------------

    @Test
    fun `la calidad mas alta gana cuando no se fija altura`() {
        val result = StreamSelection.pickFor(VIDEO_ONLY, BEST)

        // Aunque el 2160p solo exista en VP9: pedir "Mejor" es pedir la maxima
        // resolucion, y el 1080p H.264 solo se elige a igualdad de altura.
        assertEquals(2160, result?.height)
    }

    @Test
    fun `sin altura no se elige un progresivo aunque sea el de mas bitrate`() {
        // El progresivo de 720p pesa mas que cualquier pista suelta de 1080p,
        // pero "Mejor" significa maxima calidad, no el stream mas pesado.
        val result = StreamSelection.pickFor(PROGRESSIVE + VIDEO_ONLY, BEST)

        assertEquals(2160, result?.height)
        assertTrue("el resultado no debe ser un progresivo", result!!.isVideoOnly)
    }

    @Test
    fun `se respeta la altura pedida`() {
        val quality = quality(height = 360)

        assertEquals(360, StreamSelection.pickFor(VIDEO_ONLY, quality)?.height)
    }

    @Test
    fun `se respeta el fps pedido`() {
        val quality = quality(height = 1080, fps = 60)

        assertEquals(60, StreamSelection.pickFor(VIDEO_ONLY, quality)?.fps)
    }

    @Test
    fun `se prefiere H264 a igual altura porque evita recodificar`() {
        // A 1080p y 30 fps el VP9 pesa mas, pero obligaria a recodificar en el
        // dispositivo. El H.264 se puede meter en el MP4 tal cual.
        val result = StreamSelection.pickFor(VIDEO_ONLY, quality(height = 1080, fps = 30))

        assertEquals("video-1080-h264", result?.id)
    }

    @Test
    fun `devuelve null cuando ninguna altura coincide`() {
        assertNull(StreamSelection.pickFor(VIDEO_ONLY, quality(height = 4320)))
    }

    @Test
    fun `el stream elegido se resuelve como descarga de una sola pista`() {
        val result = StreamSelection.pickFor(VIDEO_ONLY, BEST)

        assertNotNull(result)
        assertEquals(result?.url, result?.toMedia()?.videoUrl)
        assertFalse("un stream suelto no pide union", result?.toMedia()?.requiresMuxing == true)
    }

    // ---------------- audio ----------------

    @Test
    fun `el audio elegido es el de mayor bitrate`() {
        assertEquals("audio-160", StreamSelection.bestAudio(AUDIO)?.id)
    }

    @Test
    fun `un audio que hay que recodificar no gana a uno que se copia`() {
        // El Opus pesa mas, pero llega en WebM: meterlo en el MP4 final obliga
        // a convertirlo a AAC en el dispositivo, que es el paso que se ha visto
        // fallar al multiplexar.
        assertEquals("audio-160", StreamSelection.bestAudio(AUDIO + OPUS)?.id)
    }

    @Test
    fun `si no hay m4a se elige el opus mas pesado`() {
        assertEquals("audio-opus-192", StreamSelection.bestAudio(listOf(OPUS))?.id)
    }

    @Test
    fun `sin streams de audio no hay mejor audio`() {
        assertNull(StreamSelection.bestAudio(emptyList()))
    }

    // ---------------- opciones del selector ----------------

    @Test
    fun `el selector no repite la misma resolucion y fps`() {
        val options = StreamSelection.videoOptions(PROGRESSIVE, VIDEO_ONLY)

        val duplicated = options.groupBy { "${it.height}x${it.fps}" }.filterValues { it.size > 1 }
        assertTrue("hay resoluciones repetidas: $duplicated", duplicated.isEmpty())
    }

    @Test
    fun `el selector ordena de mayor a menor`() {
        val heights = StreamSelection.videoOptions(PROGRESSIVE, VIDEO_ONLY).map { it.height }

        assertEquals(heights.sortedByDescending { it ?: 0 }, heights)
    }

    @Test
    fun `una resolucion con progresivo no se marca como union de pistas`() {
        val options = StreamSelection.videoOptions(PROGRESSIVE, VIDEO_ONLY)

        // 360p existe en las dos listas: gana el progresivo, que ya trae audio.
        val at360 = options.first { it.height == 360 }
        assertFalse(at360.requiresMuxing)
    }

    @Test
    fun `una resolucion sin progresivo si se marca como union de pistas`() {
        val options = StreamSelection.videoOptions(PROGRESSIVE, VIDEO_ONLY)

        // 1080p solo existe como pista suelta: obliga a unirla con el audio.
        val at1080 = options.first { it.height == 1080 }
        assertTrue(at1080.requiresMuxing)
    }

    @Test
    fun `las opciones de video marcan cuales son de video suelto`() {
        val options = StreamSelection.videoOptions(PROGRESSIVE, VIDEO_ONLY)

        assertFalse(options.first { it.height == 360 }.isVideoOnly)
        assertTrue(options.first { it.height == 1080 }.isVideoOnly)
    }

    @Test
    fun `el limite de audio recorta la lista y respeta el orden`() {
        val options = StreamSelection.audioOptions(AUDIO, limit = 2)

        assertEquals(listOf("audio-160", "audio-128"), options.map { it.id })
    }

    // ---------------- fixtures ----------------

    private companion object {

        /** Sin altura fija: "Mejor". */
        val BEST = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        /** Pista de video suelta de 360p, como la de DASH. */
        val PROGRESSIVE_360 = StreamCandidate(
            id = "progressive-360",
            label = "360p",
            url = "https://example.test/progressive-360",
            mimeType = "video/mp4",
            format = "mpeg_4",
            height = 360,
            fps = 30,
            bitrate = 600,
            isMp4 = true
        )

        val PROGRESSIVE = listOf(PROGRESSIVE_360)

        val VIDEO_ONLY = listOf(
            video("video-360", 360, bitrate = 500),
            // 1080p en VP9 pesa mas que el H.264, pero habria que recodificarlo.
            video("video-1080-vp9", 1080, bitrate = 4000, mp4 = false),
            video("video-1080-h264", 1080, bitrate = 2500),
            video("video-1080-h264-60", 1080, fps = 60, bitrate = 3000),
            video("video-2160", 2160, bitrate = 12000, mp4 = false)
        )

        val AUDIO = listOf(
            StreamCandidate(
                id = "audio-128",
                label = "128 kbps",
                url = "https://example.test/audio-128",
                mimeType = "audio/mp4",
                format = "m4a",
                bitrate = 128_000,
                isMp4 = true,
                isAudioOnly = true
            ),
            StreamCandidate(
                id = "audio-160",
                label = "160 kbps",
                url = "https://example.test/audio-160",
                mimeType = "audio/mp4",
                format = "m4a",
                bitrate = 160_000,
                isMp4 = true,
                isAudioOnly = true
            )
        )

        val OPUS = StreamCandidate(
            id = "audio-opus-192",
            label = "192 kbps",
            url = "https://example.test/audio-opus-192",
            mimeType = "audio/webm",
            format = "webm",
            bitrate = 192_000,
            isAudioOnly = true
        )
    }
}

private fun quality(height: Int? = null, fps: Int? = null) = DownloadQuality(
    id = "q",
    label = "${height ?: "best"}",
    format = "mp4",
    height = height,
    fps = fps
)

private fun video(
    id: String,
    height: Int,
    fps: Int = 30,
    bitrate: Int = 1000,
    mp4: Boolean = true
) = StreamCandidate(
    id = id,
    label = "${height}p",
    url = "https://example.test/$id",
    mimeType = if (mp4) "video/mp4" else "video/webm",
    format = if (mp4) "mpeg_4" else "webm",
    height = height,
    fps = fps,
    bitrate = bitrate,
    isMp4 = mp4,
    isVideoOnly = true,
    videoCodec = if (mp4) "avc1" else "vp9"
)
