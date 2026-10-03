package com.elimd.downloader.core.extract

import android.util.Log
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Resolutor basado en NewPipeExtractor (Java puro, sin binario externo).
 *
 * Es la implementacion por defecto: todo ocurre en el dispositivo, sin servidor
 * y sin coste. Si el extractor se rompe por un cambio de YouTube, la alternativa
 * es el plan B: montar un servidor propio con yt-dlp e implementar la misma
 * interfaz `MediaResolver`.
 */
@Singleton
class NewPipeMediaResolver @Inject constructor(
    private val downloader: OkHttpNewPipeDownloader
) : MediaResolver {

    private val service: StreamingService by lazy {
        NewPipe.init(downloader)

        // El cliente iOS de InnerTube expone muchos mas formatos que el de
        // Android (medido el 2026-10-02 con `dQw4w9WgXcQ`: ANDROID -> 1 formato,
        // IOS -> 27, VISIONOS -> 19), asi que se fuerza el suyo.
        //
        // OJO, dos cosas medidas que no son lo que parece (ver
        // ESTADO_PROYECTO.md 5.1 y 5.2):
        //  1. NO evita el bloqueo "Sign in to confirm that you're not a bot".
        //     Ese bloqueo depende de la IP, y ningun cliente lo evita: se
        //     comprobaron 7. Para eso estan las cookies de sesion, que viajan
        //     en `OkHttpNewPipeDownloader`.
        //  2. Descompilando `onFetchPage` (v0.26.5) se ve que `fetchAndroidClient`
        //     se ejecuta SIEMPRE primero y sin try/catch, y este flag solo se lee
        //     despues. O sea, anade una consulta iOS cuando Android ya funciono;
        //     si Android lanza, la rama iOS no se ejecuta.
        YoutubeStreamExtractor.setFetchIosClient(true)

        NewPipe.getService(YOUTUBE_SERVICE_ID)
    }

    /**
     * Continuaciones de busqueda vivo.
     *
     * `SearchInfo.getNextPage().url` NO es un token de continuacion: devuelve
     * la URL del endpoint, identica a la de la primera pagina. Reconstruir una
     * `Page` desde ese string produce 0 resultados. Por eso hay que conservar el
     * objeto `Page` real y solo se expone un token opaco al resto de la app.
     */
    private val pageCache = object : LinkedHashMap<String, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?) =
            size > MAX_CACHED_PAGES
    }

    private val pageCacheLock = Any()
    private val pageTokenSeq = AtomicInteger(0)

    private fun storePage(page: Page?): String? {
        if (page == null) return null
        val token = "np-${pageTokenSeq.incrementAndGet()}"
        synchronized(pageCacheLock) { pageCache[token] = page }
        return token
    }

    private fun readPage(token: String): Page? =
        synchronized(pageCacheLock) { pageCache[token] }

    override suspend fun search(query: String, pageToken: String?): SearchResult =
        withContext(Dispatchers.IO) {
            val handler = service.searchQHFactory.fromQuery(query)

            if (pageToken.isNullOrBlank()) {
                synchronized(pageCacheLock) { pageCache.clear() }
                val info = SearchInfo.getInfo(service, handler)
                SearchResult(
                    query = query,
                    videos = info.relatedItems.toVideos(),
                    nextPageToken = storePage(info.nextPage),
                    hasNextPage = info.hasNextPage()
                )
            } else {
                val page = readPage(pageToken)
                    ?: return@withContext SearchResult(query, emptyList(), null, false)
                val next = SearchInfo.getMoreItems(service, handler, page)
                SearchResult(
                    query = query,
                    videos = next.items.toVideos(),
                    nextPageToken = storePage(next.nextPage),
                    hasNextPage = next.hasNextPage()
                )
            }
        }

    override suspend fun getVideoInfo(videoId: String): YouTubeVideo? =
        withContext(Dispatchers.IO) {
            try {
                streamInfo(videoId).toYouTubeVideo()
            } catch (e: Exception) {
                // No se silencia el fallo: la UI necesita saber por que no
                // pudo obtener los datos para informar al usuario.
                Log.e(TAG, "No se pudo obtener la informacion de $videoId", e)
                throw e
            }
        }

    override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> =
        withContext(Dispatchers.IO) {
            val streams = streamInfo(videoId).candidates()
            val videos = StreamSelection.videoOptions(streams.progressive, streams.videoOnly)

            buildList {
                // "Mejor" es la maxima calidad que exista, sin fijar altura: es
                // lo que se ofrece por defecto cuando no se elige nada concreto.
                videos.maxByOrNull { it.height ?: 0 }?.let { best ->
                    add(best.copy(id = BEST_QUALITY_ID, label = BEST_LABEL, height = null, fps = null))
                }
                addAll(videos)
                addAll(StreamSelection.audioOptions(streams.audio, MAX_AUDIO_OPTIONS))
            }
        }

    override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> =
        withContext(Dispatchers.IO) {
            runCatching {
                streamInfo(videoId)
                    .relatedStreams
                    .toVideos()
                    .filter { it.videoId != videoId }
                    .take(limit)
            }.getOrDefault(emptyList())
        }

    override suspend fun resolveStream(
        videoId: String,
        quality: DownloadQuality,
        type: DownloadType
    ): ResolvedMedia = withContext(Dispatchers.IO) {
        val streams = streamInfo(videoId).candidates()

        // La decision de "que stream bajo" vive en StreamSelection, sin red y
        // cubierta por tests. Aqui solo se traduce el plan a URLs.
        val plan = StreamSelection.resolve(
            progressive = streams.progressive,
            videoOnly = streams.videoOnly,
            audio = streams.audio,
            quality = quality,
            type = type
        )
        // Sin las URLs, que son firmadas y largas: con el id, el formato y si
        // hace falta unir, una descarga fallida se puede diagnosticar desde el
        // log sin adivinar.
        Log.i(TAG, "Plan para ${quality.label} ($type): ${plan.describe()}")
        when (plan) {
            is StreamSelection.Plan.AudioOnly -> ResolvedMedia(
                videoUrl = null,
                audioUrl = plan.stream.url,
                videoMimeType = "",
                audioMimeType = plan.stream.mimeType,
                isAudioOnly = true
            )

            is StreamSelection.Plan.Single -> ResolvedMedia(
                videoUrl = plan.stream.url,
                audioUrl = null,
                videoMimeType = plan.stream.mimeType,
                audioMimeType = null,
                isAudioOnly = false,
                videoBitrateKbps = plan.stream.bitrate
            )

            is StreamSelection.Plan.Join -> ResolvedMedia(
                videoUrl = plan.video.url,
                audioUrl = plan.audio?.url,
                videoMimeType = plan.video.mimeType,
                audioMimeType = plan.audio?.mimeType,
                isAudioOnly = false,
                videoBitrateKbps = plan.video.bitrate
            )

            StreamSelection.Plan.None -> throw ExtractionException(
                "YouTube no ofrece ${quality.label} para $videoId. " +
                    "No se baja otra calidad en su lugar: se avisa para no entregar " +
                    "un fichero que no es el pedido."
            )
        }
    }

    /**
     * Traduce la respuesta del extractor al modelo con el que decide
     * [StreamSelection]. Es el unico punto del proyecto que conoce las clases de
     * NewPipeExtractor ligadas a streams.
     */
    private fun StreamInfo.candidates() = CandidateStreams(
        progressive = videoStreams.filter { it.isUrl }.map { it.toCandidate() },
        videoOnly = videoOnlyStreams.filter { it.isUrl }.map { it.toCandidate() },
        audio = audioStreams.filter { it.isUrl }.map { it.toCandidate() }
    )

    private fun VideoStream.toCandidate() = StreamCandidate(
        id = id ?: itag.toString(),
        label = buildLabel(),
        url = checkNotNull(url) { "El stream $itag se marco como descargable pero no trae URL" },
        mimeType = mimeOf(this, isAudio = false),
        format = format?.name?.lowercase() ?: "unknown",
        height = height,
        fps = fps.takeIf { it > 0 },
        bitrate = bitrate,
        isMp4 = format == MediaFormat.MPEG_4,
        isVideoOnly = isVideoOnly,
        videoCodec = codec
    )

    private fun AudioStream.toCandidate() = StreamCandidate(
        id = id ?: itag.toString(),
        label = "${averageBitrate / 1000} kbps",
        url = checkNotNull(url) { "El stream $itag se marco como descargable pero no trae URL" },
        mimeType = mimeOf(this, isAudio = true),
        format = format?.name?.lowercase() ?: "unknown",
        bitrate = averageBitrate,
        // Un m4a (AAC) es el audio que se puede copiar dentro del MP4 sin
        // recodificar; un Opus llega en WebM. Ver `StreamSelection.bestAudio`.
        isMp4 = format == MediaFormat.M4A,
        isAudioOnly = true,
        audioCodec = codec
    )

    /**
     * Resumen legible del plan, para el log del resolutor.
     */
    private fun StreamSelection.Plan.describe(): String = when (this) {
        is StreamSelection.Plan.Single -> "1 fichero ${stream.id} (${stream.format}, ${stream.height}p)"
        is StreamSelection.Plan.Join -> "unir ${video.id} (${video.format}, ${video.height}p) + ${audio?.id ?: "sin audio"}"
        is StreamSelection.Plan.AudioOnly -> "solo audio ${stream.id} (${stream.format})"
        StreamSelection.Plan.None -> "ningun stream encaja"
    }

    private data class CandidateStreams(
        val progressive: List<StreamCandidate>,
        val videoOnly: List<StreamCandidate>,
        val audio: List<StreamCandidate>
    )

    /**
     * `StreamInfo.getInfo` exige una URL completa, no un id suelto: el
     * extractor resuelve el id a traves de su LinkHandler.
     */
    private suspend fun streamInfo(videoId: String): StreamInfo =
        try {
            StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) {
            // El mensaje crudo es un volcado de stack trace de Java, que no le
            // dice nada a quien usa la app. Aqui se decide que puede hacer.
            throw ExtractionException(explain(e), e)
        }

    private fun List<org.schabi.newpipe.extractor.InfoItem>.toVideos(): List<YouTubeVideo> =
        filterIsInstance<StreamInfoItem>().map { it.toYouTubeVideo() }

    /** "1080p60" cuando hay variante de 60 fps, "1080p" si no. */
    private fun VideoStream.buildLabel(): String {
        val base = resolution ?: "${height ?: 0}p"
        val hasHighFps = fps >= HIGH_FPS
        return if (hasHighFps && !base.contains(HIGH_FPS_SUFFIX)) "$base$HIGH_FPS_SUFFIX" else base
    }

    private fun StreamInfoItem.toYouTubeVideo(): YouTubeVideo {
        val videoId = videoIdFromUrl(url)
        return YouTubeVideo(
            videoId = videoId,
            title = name,
            channelName = uploaderName.orEmpty(),
            duration = formatDuration(duration),
            viewCount = viewCount.toString(),
            uploadDate = textualUploadDate.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.width * it.height }?.url
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        )
    }

    /**
     * `StreamInfoItem` extiende `InfoItem`, que no expone el id: hay que
     * deducirlo de la URL (`?v=` o `/shorts/`).
     */
    private fun videoIdFromUrl(url: String): String {
        val fromQuery = Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1)
        if (fromQuery != null) return fromQuery
        val fromShorts = Regex("/(?:shorts|embed|live)/([\\w-]{6,})").find(url)
        return fromShorts?.groupValues?.get(1).orEmpty()
    }

    private fun StreamInfo.toYouTubeVideo() = YouTubeVideo(
        videoId = id,
        title = name,
        channelName = uploaderName.orEmpty(),
        duration = formatDuration(duration),
        viewCount = viewCount.toString(),
        uploadDate = textualUploadDate.orEmpty(),
        thumbnailUrl = thumbnails.maxByOrNull { it.width * it.height }?.url
            ?: "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    )

    /**
     * MIME del stream. `WEBM` y `WEBMA` comparten formato en el extractor pero
     * no el tipo: el mismo valor significa video o audio segun de donde venga.
     */
    private fun mimeOf(stream: Stream, isAudio: Boolean): String = when (stream.format) {
        MediaFormat.M4A -> "audio/mp4"
        MediaFormat.MP3, MediaFormat.MP2 -> "audio/mpeg"
        MediaFormat.OPUS -> "audio/ogg"
        MediaFormat.WEBM, MediaFormat.WEBMA ->
            if (isAudio) "audio/webm" else "video/webm"
        MediaFormat.WEBMA_OPUS -> "audio/ogg"
        MediaFormat.MPEG_4 -> "video/mp4"
        else -> if (isAudio) "audio/mp4" else "video/mp4"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    companion object {
        const val YOUTUBE_SERVICE_ID = 0
        const val BEST_QUALITY_ID = "best"
        private const val BEST_LABEL = "Mejor"
        private const val PROGRESSIVE_LIMIT = StreamSelection.MAX_PROGRESSIVE_HEIGHT
        private const val MAX_AUDIO_OPTIONS = 4
        private const val HIGH_FPS = 60
        private const val HIGH_FPS_SUFFIX = "60"
        private const val MAX_CACHED_PAGES = 8
        private const val TAG = "NewPipeMediaResolver"
    }
}
