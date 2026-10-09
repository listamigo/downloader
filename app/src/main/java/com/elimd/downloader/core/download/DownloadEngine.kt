package com.elimd.downloader.core.download

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.elimd.downloader.core.extract.MediaResolver
import com.elimd.downloader.core.extract.ResolvedMedia
import com.elimd.downloader.data.source.DownloadEngine
import com.elimd.downloader.data.source.DownloadEngineProgress
import com.elimd.downloader.data.source.DownloadEngineResult
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "DownloadEngine"

/** Espera entre reintentos al hueco de descarga cuando el limite lo bloquea. */
private const val SLOT_RETRY_MS = 250L

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
    private val muxer: MediaMuxer,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : DownloadEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeDownloads = ConcurrentHashMap<Long, DownloadJob>()

    // Limite de "descargas concurrentes" de Ajustes: sin esto el usuario ponia
    // 1 y seguian yendo 4 en paralelo.
    private val slots = DownloadSlots()

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

        activeDownloads[downloadId] = DownloadJob(
            downloadId,
            request,
            null,
            initialProgress(downloadId, request.target.nameWithoutExtension)
        )

        launchDownload(downloadId, request)
        return DownloadEngineResult(success = true, pid = downloadId)
    }

    private fun launchDownload(downloadId: Long, request: DownloadRequest) {
        // Solo se olvida la descarga cuando llega a un estado terminal. Si la
        // cancelacion viene de una pausa, la entrada se conserva para que
        // `resumeDownload` pueda relanzarla.
        val settled = AtomicBoolean(false)
        // Arranque perezoso: el job no puede terminar (ni ejecutar
        // invokeOnCompletion) antes de que se guarde su referencia. Con
        // arranque inmediato, una descarga que falla rapido se completaba
        // entre `launch` y el registro del handler, y la entrada quedaba en el
        // mapa con `job = null`: pause/cancel dejaban de poder tocarla.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitDownloadSlot()
                try {
                    val media = mediaResolver.resolveStream(request.videoId, request.quality, request.type)
                    val file = if (media.needsJoining()) {
                        downloadPartsAndMux(downloadId, request, media)
                    } else {
                        downloadSingle(downloadId, request, media)
                    }
                    publish(downloadId, 100f, file.length(), file.length(), status = DownloadStatus.COMPLETED)
                    settled.set(true)
                } finally {
                    slots.release()
                }
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
        val entry = activeDownloads[downloadId]
        if (entry == null) {
            // Cancelada mientras se preparaba: lanzarla dejaria un trabajo
            // huerfano escribiendo en un fichero que ya se descarto.
            job.cancel()
            return
        }
        activeDownloads[downloadId] = entry.copy(job = job)
        startDownloadService()
        job.start()
    }

    /**
     * Espera a que haya hueco para una descarga mas, segun el limite que el
     * usuario tenga en Ajustes. Mientras espera, el estado sigue siendo
     * QUEUED: todavia no se transfiere ningun byte. El limite se relee en
     * cada intento para reaccionar si el usuario lo cambia con descargas en
     * marcha. Si se cancela aqui nunca se compro hueco, y por eso el
     * `finally` que llama a [DownloadSlots.release] va dentro de este metodo
     * y no fuera.
     */
    private suspend fun awaitDownloadSlot() {
        while (true) {
            val limit = settings.getSettings().maxConcurrentDownloads
            if (slots.tryAcquire(limit)) return
            delay(SLOT_RETRY_MS)
        }
    }

    /**
     * Arranca el foreground service de forma que la descarga sobreviva a salir
     * de la app. Si el sistema lo impide (Android 12+ en segundo plano), la
     * descarga sigue adelante: solo se pierde la notificacion.
     */
    private fun startDownloadService() {
        val intent = Intent(context, DownloadService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "No se pudo arrancar DownloadService: ${e.message}")
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
        // cancel() es asincrono: sin join el corrutina viejo seguiria
        // escribiendo en el mismo fichero que acaba de relanzarse, y los dos
        // trozos acabarian en el mismo archivo.
        entry.job?.cancel()
        entry.job?.join()
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
        // La entrada ya no existe, asi que publishStatus no tendria nada que
        // actualizar: sin esta emision el servicio de foreground jamas veria el
        // CANCELLED y se quedaria colgado con la notificacion abierta.
        _progressFlow.value = entry.progress.copy(status = DownloadStatus.CANCELLED)
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
        // El scope pertenece a un @Singleton: cancelarlo dejaria el motor muerto
        // para siempre, y startDownload seguira devolviendo `success = true` sin
        // lanzar nada. Con cancelar el trabajo activo basta.
        cancelAllDownloads()
    }

    private fun publishStatus(downloadId: Long, status: DownloadStatus) {
        val current = activeDownloads[downloadId]?.progress ?: return
        // computeIfPresent en vez de getValue: entre la lectura y la escritura
        // cancelDownload puede haber borrado la clave, y getValue lanzaria
        // NoSuchElementException en mitad de una pausa.
        activeDownloads.computeIfPresent(downloadId) { _, value ->
            value.copy(progress = value.progress.copy(status = status))
        }
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
        val previous = activeDownloads[downloadId]
        val snapshot = DownloadEngineProgress(
            pid = downloadId,
            downloadId = downloadId,
            progress = percent,
            speed = rate.bytesPerSecond,
            eta = rate.remainingSeconds,
            totalSize = total,
            downloaded = downloaded,
            status = status,
            fileName = previous?.progress?.fileName,
            error = error
        )
        if (previous != null) activeDownloads[downloadId] = previous.copy(progress = snapshot)
        _progressFlow.value = snapshot
        // Solo cambios de estado: los ticks llegan a 150 ms x 4 segmentos, y el
        // propio `launchDownload` razona sobre "centenas de ticks por segundo".
        if (previous?.progress?.status != status) {
            Log.d(TAG, "download=$downloadId $status $percent% error=$error")
        }
    }

    private fun initialProgress(downloadId: Long, fileName: String) = DownloadEngineProgress(
        pid = downloadId,
        downloadId = downloadId,
        progress = 0f,
        speed = 0L,
        eta = 0L,
        totalSize = 0L,
        downloaded = 0L,
        status = DownloadStatus.QUEUED,
        fileName = fileName
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
