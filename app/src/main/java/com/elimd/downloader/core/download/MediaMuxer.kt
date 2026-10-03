package com.elimd.downloader.core.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Une la pista de video y la de audio en un unico MP4.
 *
 * YouTube solo entrega video y audio juntos (progresivo) hasta ~720p. Por encima
 * de eso hay que bajar dos ficheros y multiplicarlos, que es lo que hace esta
 * clase.
 *
 * Hay dos caminos, y cual se usa lo decide si las pistas necesitan cambio de
 * formato:
 *
 * 1. **[Mp4Remuxer]**, que copia las muestras sin codificar. Es el camino
 *    normal: lo usa siempre que el video sea H.264 en MP4 y el audio AAC en
 *    MP4, que es justo lo que el selector pide. Pesa lo que las partes y tarda
 *    segundos.
 * 2. **`media3-transformer`**, que solo se usa cuando hay que convertir de
 *    verdad (un VP9 a H.264, un Opus a AAC). Recodifica, y por eso el
 *    archivo pesa mas y tarda mas: es el precio de no ofrecer esa calidad en el
 *    formato original.
 *
 * Que el camino rapido no sea el de media3 no es casual: su optimizacion de no
 * codificar solo cubre un unico fichero MP4, y aqui las pistas vienen en dos
 * ficheros distintos. Ver `Mp4Remuxer`.
 *
 * `Transformer` exige un hilo con `Looper`, asi que en el segundo camino se crea
 * uno dedicado y se espera el resultado desde el hilo de la corrutina.
 */
@Singleton
class MediaMuxer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remuxer: Mp4Remuxer
) {

    /**
     * Combina [videoFile] y [audioFile] en [outputFile].
     *
     * @param audioFile `null` para dejar el video sin audio.
     * @param videoMimeType formato de la pista de video tal como la bajo YouTube.
     * @param audioMimeType formato de la pista de audio, `null` si no hay.
     * @param videoBitrateKbps bitrate del video de origen, en kbps. Solo se usa
     *   si hay que recodificar: al copiar, el bitrate es el que traia el origen.
     * @param onProgress fraccion 0..1 del multiplexado.
     */
    suspend fun mux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        videoMimeType: String,
        audioMimeType: String?,
        videoBitrateKbps: Int = 0,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(videoFile.exists() && videoFile.length() > 0L) {
            "El fichero de video a multiplexar no existe o esta vacio: $videoFile"
        }
        if (audioFile != null) {
            require(audioFile.exists() && audioFile.length() > 0L) {
                "El fichero de audio a multiplexar no existe o esta vacio: $audioFile"
            }
        }

        // Camino primero: sin codificar. Es el que decide el peso del archivo
        // final, asi que se intenta siempre que los contenedores lo permitan.
        if (canRemuxWithoutReencoding(videoMimeType, audioMimeType)) {
            try {
                return@withContext remuxer.remux(videoFile, audioFile, outputFile, onProgress)
            } catch (e: RemuxNotPossible) {
                // Los contenedores lo decian bien pero el movil no lo soporta, o
                // la pista no trae lo que el MP4 necesita. No es un fallo de la
                // descarga: es la señal de que toca convertir.
                Log.w(TAG, "Sin remux (${e.reason}); se recodifica con media3")
            }
        }

        transform(videoFile, audioFile, outputFile, videoMimeType, audioMimeType, videoBitrateKbps, onProgress)
    }

    /**
     * Recodifica con `media3-transformer`.
     *
     * Solo se llega aqui cuando hay un cambio de formato real: un VP9 o un AV1
     * que hay que pasar a H.264, un Opus que hay que pasar a AAC, o un
     * contenedor que no es MP4. Es lento y engorda el archivo, y no hay forma de
     * evitarlo: no se puede meter un VP9 dentro de un MP4 sin convertirlo.
     */
    private suspend fun transform(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        videoMimeType: String,
        audioMimeType: String?,
        videoBitrateKbps: Int,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val failure = AtomicReference<Throwable?>(null)
        val finished = CountDownLatch(1)
        val transformerRef = AtomicReference<Transformer?>(null)
        val progressHolder = ProgressHolder()

        val worker = HandlerThread("elimd-muxer").apply { start() }
        val looper = worker.looper
        val poller = Handler(looper)

        // El Transformer se construye y se arranca DENTRO del hilo con Looper:
        // la libreria exige que el hilo que lo crea tenga uno.
        val pollerTask = object : Runnable {
            override fun run() {
                val transformer = transformerRef.get() ?: return
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                poller.postDelayed(this, PROGRESS_POLL_MS)
            }
        }

        poller.post {
            try {
                val sequences = buildList {
                    add(
                        EditedMediaItemSequence(
                            EditedMediaItem.Builder(MediaItem.fromUri(videoFile.toUri())).build()
                        )
                    )
                    if (audioFile != null) {
                        add(
                            EditedMediaItemSequence(
                                EditedMediaItem.Builder(MediaItem.fromUri(audioFile.toUri()))
                                    .setRemoveVideo(true)
                                    .build()
                            )
                        )
                    }
                }

                val builder = Transformer.Builder(context)
                builder
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)

                // Aqui si hay que codificar, y el codificador se inventa sus
                // parametros: sin un limite, un H.264 Baseline de 450 kbps sale
                // como High de 1,9 Mbps y un archivo de 18 MB se convierte en uno
                // de 62 MB que tarda mas de lo que tardo en bajarlo.
                val targetBitrate = sourceBitrate(videoFile, videoBitrateKbps)
                Log.i(
                    TAG,
                    "Recodificando ${videoFile.name} (${videoFile.length()} B) + " +
                        "${audioFile?.name} (${audioFile?.length() ?: 0} B); " +
                        "bitrate de salida: ${targetBitrate ?: "el que ponga media3"}"
                )
                targetBitrate?.let { bitrate ->
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(bitrate)
                                    // Por defecto esta palanca esta activada y su
                                    // proposito es justo subir la calidad por
                                    // encima de lo pedido: con ella puesta, los
                                    // 446 kbps del origen salen como 1,6 Mbps.
                                    .experimentalSetEnableHighQualityTargeting(false)
                                    .build()
                            )
                            .build()
                    )
                }

                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            // El bitrate real de lo exportado:Registrarlo evita
                            // tener que bajar el archivo del movil para medirlo.
                            Log.i(
                                TAG,
                                "Exportado: video ${exportResult.averageVideoBitrate} bps, " +
                                    "audio ${exportResult.averageAudioBitrate} bps"
                            )
                            onProgress(1f)
                            finished.countDown()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            failure.set(exportException)
                            finished.countDown()
                        }
                    })
                    .build()

                transformerRef.set(transformer)
                transformer.start(Composition.Builder(sequences).build(), outputFile.absolutePath)
                poller.post(pollerTask)
            } catch (e: Throwable) {
                failure.set(e)
                finished.countDown()
            }
        }

        try {
            // Espera en fracciones cortas para notar la cancelacion de la
            // corrutina: `await()` a pelo bloquea hasta que el Transformer acaba.
            while (finished.count > 0L) {
                coroutineContext.ensureActive()
                if (finished.await(PROGRESS_POLL_MS, TimeUnit.MILLISECONDS)) break
            }
            coroutineContext.ensureActive()
        } catch (e: Exception) {
            poller.post { transformerRef.get()?.cancel() }
            throw e
        } finally {
            poller.removeCallbacks(pollerTask)
            worker.quitSafely()
        }

        failure.get()?.let { throw it }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("El multiplexado no produjo ninguna salida en $outputFile")
        }
        Log.i(TAG, "Multiplexado correcto: ${videoFile.name} + ${audioFile?.name} -> ${outputFile.name}")
        outputFile
    }

    private companion object {
        const val TAG = "MediaMuxer"
        const val PROGRESS_POLL_MS = 250L
    }
}

/**
 * Si las pistas se pueden copiar tal cual a un MP4.
 *
 * Es la condicion para que exista un camino sin codificar, y se decide por el
 * contenedor: un MP4 con H.264 y un MP4 con AAC se pueden meter en un MP4 sin
 * tocar un byte. Con un WebM o un Ogg no, porque el MP4 no admite esos codecs.
 *
 * Lo que no llega a comprobar es si el movil soporta el codec concreto; de eso
 * se encarga `Mp4Remuxer`, que si no puede, avisa y devuelve el control.
 */
internal fun canRemuxWithoutReencoding(videoMimeType: String, audioMimeType: String?): Boolean {
    val videoFits = videoMimeType.contains("mp4")
    val audioFits = audioMimeType == null || audioMimeType.contains("mp4")
    return videoFits && audioFits
}

/**
 * Si las pistas hay que convertirlas para meterlas en un MP4.
 *
 * Al revés que [canRemuxWithoutReencoding], y para el mismo decision: lo que no
 * se puede copiar, media3 lo tiene que recodificar.
 */
internal fun needsMuxConversion(videoMimeType: String, audioMimeType: String?): Boolean =
    !canRemuxWithoutReencoding(videoMimeType, audioMimeType)

/**
 * El bitrate de origen en bits por segundo, que es lo que espera el encoder.
 *
 * El extractor no siempre lo publica (InnerTube lo deja a veces a cero), asi que
 * cuando no lo dice se mide del propio fichero: los bytes que ocupa y los
 * segundos que dura dan el mismo numero, y es la unica fuente que no depende de
 * que YouTube haya rellenado el campo.
 *
 * Se acota por los dos lados porque un bitrate absurdo (0, o un stream que venga
 * ya en bps) hace que el encoder produzca un archivo inservible en lugar de
 * fallar limpio.
 */
internal fun sourceBitrate(videoFile: File, declaredKbps: Int): Int? =
    sourceBitrateOrNull(declaredKbps) ?: measuredBitrate(videoFile, durationMsOf(videoFile))

/** El bitrate que declara el extractor, en kbps, convertido a bits por segundo. */
internal fun sourceBitrateOrNull(videoBitrateKbps: Int): Int? {
    if (videoBitrateKbps <= 0) return null
    val bitsPerSecond = videoBitrateKbps.toLong() * 1000
    return bitsPerSecond.takeIf { it in MIN_SOURCE_BITRATE..MAX_SOURCE_BITRATE }?.toInt()
}

/** bits por segundo medidos sobre el fichero: tamaño y duración. */
internal fun measuredBitrate(videoFile: File, durationMs: Long?): Int? {
    if (durationMs == null || durationMs <= 0L) return null
    val length = videoFile.length()
    if (length <= 0L) return null

    val bitsPerSecond = length.toBigInteger() * BigInteger.valueOf(8) * BigInteger.valueOf(1000) /
        BigInteger.valueOf(durationMs)
    if (bitsPerSecond < BigInteger.valueOf(MIN_SOURCE_BITRATE) ||
        bitsPerSecond > BigInteger.valueOf(MAX_SOURCE_BITRATE)
    ) {
        return null
    }
    return bitsPerSecond.toInt()
}

/** La duración del fichero, que media3 no expone antes de empezar a exportar. */
internal fun durationMsOf(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}

private const val MIN_SOURCE_BITRATE = 100_000L
private const val MAX_SOURCE_BITRATE = 50_000_000L
