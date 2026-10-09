package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El servidor entrega un archivo unico ya muxeado. Si el cliente creyera que
 * hay que unir pistas (audioUrl no null) pediria una descarga segmentada y
 * fallaria; el contrato de [ResolvedMedia] es lo que se prueba aqui.
 */
class ServerMediaResolverTest {

    private val client = OkHttpClient()

    private fun resolver(serverUrl: String = "https://server.example") =
        ServerMediaResolver(
            client = client,
            settingsRepository = FakeSettingsRepository(AppSettings(serverUrl = serverUrl))
        )

    @Test
    fun `video devuelve una sola url y no pide mux`() = runBlocking {
        val quality = DownloadQuality(id = "v:1080:25", label = "1080p", format = "mp4", height = 1080)

        val media = resolver().resolveStream("dQw4w9WgXcQ", quality, DownloadType.VIDEO)

        assertEquals(
            "https://server.example/api/media/dQw4w9WgXcQ?quality=v%3A1080%3A25&type=video",
            media.videoUrl
        )
        assertNull("el servidor ya muxea: no hay segunda pista", media.audioUrl)
        assertEquals("video/mp4", media.videoMimeType)
        assertFalse(media.requiresMuxing)
    }

    @Test
    fun `audio devuelve m4a y se marca como solo audio`() = runBlocking {
        val quality = DownloadQuality(id = "a:129", label = "129 kbps", format = "m4a", isAudioOnly = true)

        val media = resolver().resolveStream("dQw4w9WgXcQ", quality, DownloadType.AUDIO)

        assertEquals(
            "https://server.example/api/media/dQw4w9WgXcQ?quality=a%3A129&type=audio",
            media.audioUrl
        )
        assertNull(media.videoUrl)
        assertTrue(media.isAudioOnly)
        assertEquals("audio/mp4", media.audioMimeType)
        assertFalse(media.requiresMuxing)
    }

    @Test
    fun `la barra final de la url no duplica la ruta`() = runBlocking {
        val quality = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        val media = resolver(serverUrl = "https://server.example/").resolveStream(
            "abc", quality, DownloadType.VIDEO
        )

        assertEquals("https://server.example/api/media/abc?quality=best&type=video", media.videoUrl)
    }

    @Test(expected = ServerNotConfiguredException::class)
    fun `sin url configurada no se intenta bajar nada`(): Unit = runBlocking {
        val quality = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        resolver(serverUrl = "   ").resolveStream("abc", quality, DownloadType.VIDEO)
    }

    @Test
    fun `el dto de calidad se mapea al modelo de dominio`() {
        val dto = ServerQualityDto(
            id = "v:720:60",
            label = "720p60",
            format = "mp4",
            height = 720,
            fps = 60,
            videoCodec = "avc1",
            audioCodec = "mp4a",
            fileSize = 1234L
        )

        val quality = dto.toDomain()

        assertEquals("v:720:60", quality.id)
        assertEquals(720, quality.height)
        assertEquals(60, quality.fps)
        assertEquals(1234L, quality.fileSize)
        assertFalse(quality.isAudioOnly)
    }

    @Test
    fun `el dto de video se mapea al modelo de dominio`() {
        val dto = ServerVideoDto(
            videoId = "abc",
            title = "Titulo",
            channelName = "Canal",
            duration = "3:21",
            isLive = true
        )

        val video = dto.toDomain()

        assertEquals("abc", video.videoId)
        assertEquals("Titulo", video.title)
        assertEquals("Canal", video.channelName)
        assertTrue(video.isLive)
    }
}
