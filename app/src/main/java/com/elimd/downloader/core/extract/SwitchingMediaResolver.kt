package com.elimd.downloader.core.extract

import com.elimd.downloader.core.di.LocalResolver
import com.elimd.downloader.core.di.RemoteResolver
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Elige en cada llamada entre el extractor local ([NewPipeMediaResolver]) y el
 * backend propio ([ServerMediaResolver]) segun el ajuste "Usar servidor".
 *
 * La decision se evalua por operacion, no al construir el grafo: si el usuario
 * enciende o apaga el interruptor, la siguiente busqueda o descarga ya respeta
 * el cambio sin reiniciar la app.
 *
 * No hay fallback silencioso de uno a otro: si el modo elegido falla, el error
 * sube tal cual. Bajar por la otra ruta sin avisar entregaria un archivo de una
 * fuente distinta a la pedida, o escondería que el servidor no responde.
 */
class SwitchingMediaResolver @Inject constructor(
    @LocalResolver private val local: MediaResolver,
    @RemoteResolver private val remote: MediaResolver,
    private val settingsRepository: SettingsRepository
) : MediaResolver {

    private suspend fun active(): MediaResolver =
        if (settingsRepository.getSettings().useRemoteServer) remote else local

    override suspend fun search(query: String, pageToken: String?): SearchResult =
        active().search(query, pageToken)

    override suspend fun getVideoInfo(videoId: String): YouTubeVideo? =
        active().getVideoInfo(videoId)

    override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> =
        active().getAvailableQualities(videoId)

    /**
     * Los relacionados siempre salen del extractor local: el servidor no los
     * ofrece (yt-dlp no los expone) y pedirlos localmente es barato. Esto no es
     * un fallback de la operacion principal, solo una fuente distinta para un
     * dato complementario.
     */
    override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> =
        local.getRelatedVideos(videoId, limit)

    override suspend fun resolveStream(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): ResolvedMedia = active().resolveStream(videoId, quality, type)
}
