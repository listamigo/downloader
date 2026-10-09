package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El interruptor "Usar servidor" decide la fuente por operacion. Un fallo del
 * enrutado mandaria la descarga al backend equivocado, y el fallback silencioso
 * que se prohibio escondería que el servidor no responde.
 */
class SwitchingMediaResolverTest {

    private class RecordingResolver : MediaResolver {
        var calls = mutableListOf<String>()
        var reloaded = ""

        override suspend fun search(query: String, pageToken: String?): SearchResult {
            calls += "search"
            return SearchResult(query = query, videos = emptyList())
        }

        override suspend fun getVideoInfo(videoId: String): YouTubeVideo? {
            calls += "video"
            return null
        }

        override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> {
            calls += "qualities"
            return emptyList()
        }

        override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> {
            calls += "related"
            return emptyList()
        }

        override suspend fun resolveStream(
            videoId: String,
            quality: DownloadQuality,
            type: DownloadType
        ): ResolvedMedia {
            calls += "stream"
            return ResolvedMedia(null, null, "", null, true)
        }
    }

    private val local = RecordingResolver()
    private val remote = RecordingResolver()

    private fun resolver(useRemote: Boolean): SwitchingMediaResolver =
        SwitchingMediaResolver(
            local = local,
            remote = remote,
            settingsRepository = FakeSettingsRepository(AppSettings(useRemoteServer = useRemote))
        )

    @Test
    fun `con el modo apagado todo va al resolutor local`() = runBlocking {
        val resolver = resolver(useRemote = false)
        val quality = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        resolver.search("q", null)
        resolver.getVideoInfo("id")
        resolver.getAvailableQualities("id")
        resolver.resolveStream("id", quality, DownloadType.VIDEO)

        assertEquals(listOf("search", "video", "qualities", "stream"), local.calls)
        assertEquals(emptyList<String>(), remote.calls)
    }

    @Test
    fun `con el modo encendido todo va al resolutor remoto`() = runBlocking {
        val resolver = resolver(useRemote = true)
        val quality = DownloadQuality(id = "best", label = "Mejor", format = "mp4")

        resolver.search("q", null)
        resolver.getVideoInfo("id")
        resolver.getAvailableQualities("id")
        resolver.resolveStream("id", quality, DownloadType.VIDEO)

        assertEquals(listOf("search", "video", "qualities", "stream"), remote.calls)
        assertEquals(emptyList<String>(), local.calls)
    }

    @Test
    fun `los relacionados siempre salen del resolutor local`() = runBlocking {
        val resolver = resolver(useRemote = true)

        resolver.getRelatedVideos("id", 10)

        assertEquals(listOf("related"), local.calls)
        assertNull("el servidor no ofrece relacionados", remote.calls.firstOrNull())
    }

    @Test
    fun `el cambio de ajuste se aplica sin reconstruir el resolutor`() = runBlocking {
        val settings = FakeSettingsRepository(AppSettings(useRemoteServer = false))
        val resolver = SwitchingMediaResolver(local = local, remote = remote, settingsRepository = settings)

        resolver.search("q", null)
        settings.updateUseRemoteServer(true)
        resolver.search("q", null)

        assertEquals(listOf("search"), local.calls)
        assertEquals(listOf("search"), remote.calls)
    }
}
