package com.elimd.downloader.core.download

import android.util.Log
import com.elimd.downloader.core.extract.MediaResolver
import com.elimd.downloader.core.extract.ResolvedMedia
import com.elimd.downloader.data.source.DownloadEngine
import com.elimd.downloader.data.source.DownloadEngineProgress
import com.elimd.downloader.data.source.DownloadEngineResult
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "DownloadEngine"

/**
 * Motor de descargas.
 *
 * Resuelve la URL directa con un [MediaResolver] y transfiere los bytes con
 * OkHttp, sin depender de binarios externos ni de servidores. Ambas piezas son
 * intercambiables: [MediaResolver] permite cambiar a un servidor propio con
 * yt-dlp (plan B) sin tocar este motor.
 */
@Singleton
class DownloadEngineImpl @Inject constructor(
    private val mediaResolver: MediaResolver,
    private val fileDownloader: HttpFileDownloader,
    private val muxer: MediaMuxer
) : DownloadEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeDownloads = ConcurrentHashMap<Long, DownloadJob>()

    // StateFlow y no SharedFlow: el progreso es estado, no un evento. Con un
    // buffer limitado, tryEmit descarta el valor cuando el consumidor va lento
    // (pasa al final de la descarga, con cientos de ticks por segundo) y el
    // evento COMPLETED se pierde. StateFlow siempre converge al ultimo valor.
    private val _progressFlow = MutableStateFlow<DownloadEngineProgress?>(null)

    override val progressFlow: StateFlow<DownloadEngineProgress?> = _progressFlow.asStateFlow()

    override suspend fun startDownload(
        downloadId: Long,
        videoId: String,
        url: String,
        quality: DownloadQuality,
        type: DownloadType,
        outputDir: String,
        fileName: String
    ): DownloadEngineResult {
        val target = File(outputDir, "$fileName.${extensionFor(type)}")
        target.parentFile?.mkdirs()

        val request = DownloadRequest(
            videoId = videoId,
            quality = quality,
            type = type,
            target = target
        )

        // Un reintento no puede convivir con el intento anterior. Los dos
        // escribirian en el mismo fichero a la vez y cada uno anadiria su trozo
        // al final: el resultado es un archivo con el contenido duplicado, que
        // es como un video de 4 minutos acaba ocupando 60 MB.
        activeDownloads.remove(downloadId)?.job?.cancel()

        activeDownloads[downloadId] = DownloadJob(downloadId, request, null, initialProgress(downloadId))

        launchDownload(downloadId, request)
        return DownloadEngineResult(success = true, pid = downloadId)
    }

    private fun launchDownload(downloadId: Long, request: DownloadRequest) {
        // Solo se olvida la descarga cuando llega a un estado terminal. Si la
        // cancelacion viene de una pausa, la entrada se conserva para que
        // `resumeDownload` pueda relanzarla.
        val settled = AtomicBoolean(false)
        val job = scope.launch {
            try {
                val media = mediaResolver.resolveStream(request.videoId, request.quality, request.type)
                val file = if (media.needsJoining()) {
                    downloadPartsAndMux(downloadId, request, media)
                } else {
                    downloadSingle(downloadId, request, media)
                }
                publish(downloadId, 100f, file.length(), file.length(), status = DownloadStatus.COMPLETED)
                settled.set(true)
            } catch (e: CancellationException) {
                // La descarga se pauso o cancelo: no es un fallo.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fallo la descarga de ${request.videoId}", e)
                publish(
                    downloadId = downloadId,
                    percent = 0f,
                    downloaded = 0L,
                    total = 0L,
                    status = DownloadStatus.FAILED,
                    error = e.message
                )
                settled.set(true)
            }
        }
        job.invokeOnCompletion {
            if (settled.get()) activeDownloads.remove(downloadId)
        }
        activeDownloads[downloadId]?.let { entry ->
            activeDownloads[downloadId] = entry.copy(job = job)
        }
    }

    /** Un unico stream: se descarga directo al destino, sin pasos intermedios. */
    private suspend fun downloadSingle(
        downloadId: Long,
        request: DownloadRequest,
        media: ResolvedMedia
    ): File {
        val url = media.videoUrl ?: media.audioUrl
            ?: throw IllegalStateException("El resolutor no devolvio ninguna URL")

        val eta = DownloadEta()
        return fileDownloader.download(url, request.target) { downloaded, total ->
            val ratio = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
            val percent = ratio * 100f
            publish(
                downloadId = downloadId,
                percent = percent,
                downloaded = downloaded,
                total = total,
                rate = eta.sample(percent, downloaded),
                status = DownloadStatus.DOWNLOADING
            )
        }
    }

    /**
     * Video y audio van en ficheros separados: se baja cada uno por su lado y se
     * unen en un unico MP4. Es lo que permite calidades por encima de 720p.
     *
     * Los dos van **a la vez**. En serie, el tiempo es la suma de los dos, y con
     * el ancho de banda repartido en una sola conexion eso se nota; si la
     * conexion va sobrada, da lo mismo. De paso el peso total de la descarga
     * solo puede subir, en vez de reiniciarse al empezar el audio.
     *
     * Los parciales se llaman siempre igual, de forma que al reanudar una pausa
     * `HttpFileDownloader` los continua con una cabecera `Range` en vez de
     * volver a bajar los bytes ya pagados.
     */
    private suspend fun downloadPartsAndMux(
        downloadId: Long,
        request: DownloadRequest,
        media: ResolvedMedia
    ): File {
        val videoUrl = requireNotNull(media.videoUrl) { "Falta la URL de video" }
        val audioUrl = media.audioUrl
        // La extension del parcial no es cosmetica: media3 decide como leer un
        // fichero por su nombre, y un "video.mp4.video" no le dice nada. Con la
        // extension real del contenedor entra el extractor correcto.
        val videoPart = partFile(request.target, VIDEO_PART, extensionFor(media.videoMimeType))
        val audioPart = audioUrl?.let {
            partFile(request.target, AUDIO_PART, extensionFor(media.audioMimeType.orEmpty()))
        }

        val videoBytes = AtomicLong()
        val videoTotal = AtomicLong()
        val audioBytes = AtomicLong()
        val audioTotal = AtomicLong()
        val eta = DownloadEta()

        fun publishParts() {
            val downloaded = videoBytes.get() + audioBytes.get()
            val total = videoTotal.get() + audioTotal.get()
            val ratio = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
            // Lo que se descarga se lleva el 90% de la barra; el multiplexado, el
            // 10% restante. Las dos pistas avanzan a la vez, asi que no hay fase
            // que repartan: el peso total solo puede subir.
            val percent = ratio * MUX_FROM * 100f
            publish(
                downloadId = downloadId,
                percent = percent,
                downloaded = downloaded,
                total = total,
                rate = eta.sample(percent, downloaded),
                status = DownloadStatus.DOWNLOADING
            )
        }

        coroutineScope {
            val video = async {
                fileDownloader.download(videoUrl, videoPart) { downloaded, total ->
                    videoBytes.set(downloaded)
                    videoTotal.set(total)
                    publishParts()
                }
            }
            val audio = audioUrl?.let { url ->
                async {
                    fileDownloader.download(url, checkNotNull(audioPart)) { downloaded, total ->
                        audioBytes.set(downloaded)
                        audioTotal.set(total)
                        publishParts()
                    }
                }
            }
            video.await()
            audio?.await()
        }

        val output = muxer.mux(
            videoFile = videoPart,
            audioFile = audioPart,
            outputFile = request.target,
            videoMimeType = media.videoMimeType,
            audioMimeType = media.audioMimeType,
            videoBitrateKbps = media.videoBitrateKbps
        ) { fraction ->
            val percent = (MUX_FROM + fraction * (MUX_TO - MUX_FROM)) * 100f
            publish(
                downloadId = downloadId,
                percent = percent,
                downloaded = videoTotal.get() + audioTotal.get(),
                total = videoTotal.get() + audioTotal.get(),
                rate = eta.sample(percent, videoBytes.get() + audioBytes.get()),
                status = DownloadStatus.DOWNLOADING
            )
        }

        videoPart.delete()
        audioPart?.delete()
        return output
    }

    /**
     * Si el resultado tiene que pasar por el muxer.
     *
     * Dos casos, y el segundo es el que se pasa por alto: con audio, porque
     * hay dos pistas que unir; y **tambien sin audio**, porque la pista suelta
     * de "solo video" por encima de 720p suele venir en WebM, y guardarla con
     * extension `.mp4` produce un fichero que muchos reproductores no abren.
     */
    private fun ResolvedMedia.needsJoining(): Boolean {
        if (isAudioOnly) return false
        val video = videoUrl ?: return false
        return audioUrl != null || videoMimeType != MP4_VIDEO_MIME
    }

    private fun partFile(target: File, part: String, extension: String) =
        File(target.parentFile, "${target.name}.$part.$extension")

    /**
     * Borra los parciales de una descarga sean del contenedor que sean.
     *
     * No se guardan sus nombres porque la extension de cada uno depende del MIME
     * de su pista, y un parcial que sobrevive a una cancelacion envenena el
     * siguiente intento: el reintento lo encontraria a medias y lo daria por
     * bueno.
     */
    private fun discardParts(target: File) {
        val prefixes = listOf("${target.name}.$VIDEO_PART.", "${target.name}.$AUDIO_PART.")
        target.parentFile
            ?.listFiles { file -> prefixes.any { file.name.startsWith(it) } }
            ?.forEach { fileDownloader.discardPartial(it) }
    }

    /** Extension del contenedor que media3 entiende para un MIME dado. */
    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("webm") -> "webm"
        mimeType.contains("ogg") -> "ogg"
        mimeType.contains("mpeg") -> "mp3"
        else -> "mp4"
    }

    override suspend fun pauseDownload(pid: Long) {
        val entry = activeDownloads[pid] ?: return
        entry.job?.cancel(CancellationException("Descarga pausada"))
        publishStatus(pid, DownloadStatus.PAUSED)
    }

    override suspend fun resumeDownload(pid: Long) {
        val entry = activeDownloads[pid] ?: return
        entry.job?.cancel()
        publishStatus(pid, DownloadStatus.QUEUED)
        launchDownload(pid, entry.request)
    }

    override suspend fun cancelDownload(pid: Long) {
        val entry = activeDownloads.remove(pid) ?: return
        entry.job?.cancel(CancellationException("Descarga cancelada"))
        fileDownloader.discardPartial(entry.request.target)
        // Una descarga con multiplexado deja video y audio en ficheros aparte:
        // si no se van, la siguiente conservaria un audio a medias.
        discardParts(entry.request.target)
        publishStatus(pid, DownloadStatus.CANCELLED)
    }

    override suspend fun cancelAllDownloads() {
        activeDownloads.keys.toList().forEach { cancelDownload(it) }
    }

    override suspend fun getActiveDownloads(): List<DownloadEngineProgress> =
        activeDownloads.values.map { it.progress }

    override suspend fun isRunning(pid: Long): Boolean = activeDownloads.containsKey(pid)

    override suspend fun getProgress(pid: Long): DownloadEngineProgress? =
        activeDownloads[pid]?.progress

    override suspend fun cleanup() {
        cancelAllDownloads()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun publishStatus(downloadId: Long, status: DownloadStatus) {
        val current = activeDownloads[downloadId]?.progress ?: return
        activeDownloads[downloadId] = activeDownloads.getValue(downloadId).copy(
            progress = current.copy(status = status)
        )
        _progressFlow.value = current.copy(status = status)
    }

    private fun publish(
        downloadId: Long,
        percent: Float,
        downloaded: Long,
        total: Long,
        rate: DownloadEta.Sample = DownloadEta.Sample.NONE,
        status: DownloadStatus,
        error: String? = null
    ) {
        val snapshot = DownloadEngineProgress(
            pid = downloadId,
            downloadId = downloadId,
            progress = percent,
            speed = rate.bytesPerSecond,
            eta = rate.remainingSeconds,
            totalSize = total,
            downloaded = downloaded,
            status = status
        )
        activeDownloads[downloadId]?.let {
            activeDownloads[downloadId] = it.copy(progress = snapshot)
        }
        _progressFlow.value = snapshot
        Log.d(TAG, "download=$downloadId $status $percent% error=$error")
    }

    private fun initialProgress(downloadId: Long) = DownloadEngineProgress(
        pid = downloadId,
        downloadId = downloadId,
        progress = 0f,
        speed = 0L,
        eta = 0L,
        totalSize = 0L,
        downloaded = 0L,
        status = DownloadStatus.QUEUED
    )

    private fun extensionFor(type: DownloadType): String = when (type) {
        DownloadType.AUDIO -> "m4a"
        DownloadType.VIDEO, DownloadType.BOTH -> "mp4"
    }

    private data class DownloadRequest(
        val videoId: String,
        val quality: DownloadQuality,
        val type: DownloadType,
        val target: File
    )

    private data class DownloadJob(
        val downloadId: Long,
        val request: DownloadRequest,
        val job: Job?,
        val progress: DownloadEngineProgress
    )

    private companion object {
        const val VIDEO_PART = "video"
        const val AUDIO_PART = "audio"
        const val MP4_VIDEO_MIME = "video/mp4"

        // Reparto de la barra cuando hay dos pistas: bajar los bytes se lleva el
        // 90% y unirlos el 10% restante.
        const val MUX_FROM = 0.90f
        const val MUX_TO = 1f
    }
}
