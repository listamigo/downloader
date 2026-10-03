package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El fallo que duele: pedir 1080p y que llegue un 360p.
 *
 * Estos tests fijan esa garantia en la logica que decide que se descarga, sin
 * necesidad de red. Si alguien "optimiza" el selector para que siempre devuelva
 * algo, estos tests se rompen.
 */
class StreamResolutionPlanTest {

    @Test
    fun `pedir 1080p devuelve 1080p, no la mejor disponible por debajo`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(1080),
            type = DownloadType.BOTH
        )

        val join = plan as StreamSelection.Plan.Join
        assertEquals(1080, join.video.height)
        assertTrue("a igual altura debe ganar el H.264, que no hay que recodificar", join.video.isMp4)
    }

    @Test
    fun `cada calidad del selector devuelve su propia resolucion`() {
        listOf(360, 480, 720, 1080, 2160).forEach { pedida ->
            val plan = StreamSelection.resolve(
                progressive = PROGRESSIVE_360,
                videoOnly = VIDEO_ONLY,
                audio = AUDIO,
                quality = quality(pedida),
                type = DownloadType.BOTH
            )

            // Hasta 720p manda el progresivo (un solo fichero) y por encima la
            // pista suelta con audio aparte: lo que no puede cambiar es la altura.
            val video = videoOf(plan)
            assertEquals(
                "se pidio ${pedida}p y se iba a bajar ${video?.height ?: "nada"}",
                pedida,
                video?.height
            )
        }
    }

    @Test
    fun `si el video no tiene la calidad pedida NO se sustituye por otra`() {
        // El video solo llega hasta 720p. Pedir 1080p tiene que fallar, no
        // entregar un 720p haciendolo pasar por 1080p.
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY.takeWhile { it.height != 1080 },
            audio = AUDIO,
            quality = quality(1080),
            type = DownloadType.BOTH
        )

        assertEquals(StreamSelection.Plan.None, plan)
    }

    @Test
    fun `pedir una resolucion inexistente no degrada a la maxima disponible`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(4320),
            type = DownloadType.BOTH
        )

        assertEquals(StreamSelection.Plan.None, plan)
    }

    @Test
    fun `Mejor sí elige la maxima resolucion disponible`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = BEST,
            type = DownloadType.BOTH
        )

        val join = plan as StreamSelection.Plan.Join
        assertEquals(2160, join.video.height)
    }

    @Test
    fun `el fps pedido tambien se respeta`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(1080, fps = 30),
            type = DownloadType.BOTH
        )

        val join = plan as StreamSelection.Plan.Join
        assertEquals(30, join.video.fps)
    }

    // ---------------- rutas economicas ----------------

    @Test
    fun `hasta 720p con audio se descarga en un solo fichero`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_720,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(720),
            type = DownloadType.BOTH
        )

        // El progresivo ya trae las dos pistas: unir seria trabajo gratis.
        val single = plan as StreamSelection.Plan.Single
        assertEquals(720, single.stream.height)
    }

    @Test
    fun `un progresivo siempre gana a unir pistas`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_720,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(720),
            type = DownloadType.BOTH
        )

        assertTrue(plan is StreamSelection.Plan.Single)
    }

    @Test
    fun `solo video por encima de 720p no pide audio`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_720,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(1080),
            type = DownloadType.VIDEO
        )

        val join = plan as StreamSelection.Plan.Join
        assertEquals(1080, join.video.height)
        assertNull("en solo video no debe unirse audio", join.audio)
    }

    @Test
    fun `solo video usa la pista suelta aunque haya un progresivo`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = quality(360),
            type = DownloadType.VIDEO
        )

        val join = plan as StreamSelection.Plan.Join
        assertTrue("debe usar la pista sin audio", join.video.isVideoOnly)
        assertNull(join.audio)
    }

    @Test
    fun `solo audio devuelve audio y nada mas`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = AUDIO,
            quality = BEST,
            type = DownloadType.AUDIO
        )

        val audio = plan as StreamSelection.Plan.AudioOnly
        assertEquals("audio-160", audio.stream.id)
    }

    @Test
    fun `un video sin pistas de audio no se puede descargar como audio`() {
        val plan = StreamSelection.resolve(
            progressive = PROGRESSIVE_360,
            videoOnly = VIDEO_ONLY,
            audio = emptyList(),
            quality = BEST,
            type = DownloadType.AUDIO
        )

        assertEquals(StreamSelection.Plan.None, plan)
    }

    // ---------------- helpers ----------------

    private fun quality(height: Int?, fps: Int? = null) = DownloadQuality(
        id = "q",
        label = "${height ?: "best"}p",
        format = "mp4",
        height = height,
        fps = fps
    )

    private fun videoOf(plan: StreamSelection.Plan): StreamCandidate? = when (plan) {
        is StreamSelection.Plan.Single -> plan.stream
        is StreamSelection.Plan.Join -> plan.video
        else -> null
    }

    private companion object {

        val BEST = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        fun progressive(height: Int) = StreamCandidate(
            id = "progressive-$height",
            label = "${height}p",
            url = "https://example.test/progressive-$height",
            mimeType = "video/mp4",
            format = "mpeg_4",
            height = height,
            fps = 30,
            bitrate = 800,
            isMp4 = true
        )

        val PROGRESSIVE_360 = listOf(progressive(360))
        val PROGRESSIVE_720 = listOf(progressive(720))

        val VIDEO_ONLY = listOf(
            video("video-360", 360, bitrate = 500),
            video("video-480", 480, bitrate = 900, mp4 = false),
            video("video-720", 720, bitrate = 1800),
            video("video-1080-vp9", 1080, bitrate = 4000, mp4 = false),
            video("video-1080-h264", 1080, bitrate = 2500),
            video("video-1080-h264-60", 1080, fps = 60, bitrate = 3000),
            video("video-2160", 2160, bitrate = 12000, mp4 = false)
        )

        val AUDIO = listOf(
            StreamCandidate(
                id = "audio-128", label = "128 kbps",
                url = "https://example.test/audio-128", mimeType = "audio/mp4",
                format = "m4a", bitrate = 128_000, isMp4 = true, isAudioOnly = true
            ),
            StreamCandidate(
                id = "audio-160", label = "160 kbps",
                url = "https://example.test/audio-160", mimeType = "audio/mp4",
                format = "m4a", bitrate = 160_000, isMp4 = true, isAudioOnly = true
            )
        )
    }
}

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
