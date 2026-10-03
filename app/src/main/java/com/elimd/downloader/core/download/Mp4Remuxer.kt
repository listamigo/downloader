package com.elimd.downloader.core.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "Mp4Remuxer"

/**
 * El remux no se puede hacer y hay que decodificar y recodificar.
 *
 * No es un fallo de la descarga: es la señal de que el camino barato no aplica y
 * de que toca la ruta de [MediaMuxer] con `media3-transformer`. Por eso no
 * arrastra el stack trace ni se muestra al usuario.
 */
internal class RemuxNotPossible(val reason: String) : Exception(reason)

/**
 * Une las pistas **sin volver a codificar**.
 *
 * Es el caso normal y el que decide el peso del archivo final. Cuando el video
 * ya es H.264 y el audio AAC, los dos dentro de MP4, unirlos no es una
 * conversion: es copiar las muestras tal cual a un `moov` comun. El resultado
 * pesa lo que las partes, con la calidad exacta de origen, y tarda lo que
 * tardo en leerse del disco.
 *
 * ## Por que esto no lo hacia `media3-transformer`
 *
 * `Transformer` tiene un camino que no codifica, pero solo le sirve a **un
 * unico fichero MP4 con video y audio dentro**. Se comprueba en su
 * `remuxRemainingMedia()`, que mira unicamente
 * `composition.sequences[0].editedMediaItems[0]` y compara sus dos formatos con
 * los del muxer (`doesFormatsMatch`). Nuestra composicion tiene **dos
 * secuencias** (el video suelto de un fichero y el audio de otro), asi que ese
 * camino no es aplicable nunca y la libreria cae siempre en
 * `processFullInput()`: el video decodifica, el encoder elige sus parametros y
 * el archivo sale mas pesado. Medido: 17,9 MB de entrada, 54 MB de salida.
 *
 * Ahi no hay ningun ajuste que lo arregle: `setBitrate` lo acota, pero
 * convertir un H.264 Constrained Baseline en High profile sigue siendo
 * convertir. La unica forma de respectar la calidad de origen es no codificar,
 * y para eso esta clase.
 *
 * ## Cuando no sirve
 *
 * Se lanza [RemuxNotPossible] y quien llama cae a `media3`, que si sabe
 * convertir. Pasa con un VP9 o un AV1, con un Opus, y con cualquier pista cuyo
 * contenedor no sea MP4.
 */
@Singleton
class Mp4Remuxer @Inject constructor() {

    /**
     * Copia la pista de video de [videoFile] y la de audio de [audioFile] a un
     * unico MP4 en [outputFile].
     *
     * @param audioFile `null` para copiar solo el video.
     * @throws RemuxNotPossible si hay que convertir y quien llama debe hacerlo.
     */
    suspend fun remux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var writing = false
        var done = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = videoExtractor.requireTrack(VIDEO_MIME_PREFIX, videoFile)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)

            var audioTrack = NO_TRACK
            var audioFormat: MediaFormat? = null
            if (audioFile != null) {
                audioExtractor.setDataSource(audioFile.absolutePath)
                audioTrack = audioExtractor.requireTrack(AUDIO_MIME_PREFIX, audioFile)
                audioFormat = audioExtractor.getTrackFormat(audioTrack)
                // Sin `csd-0` el MP4 no puede escribir el `esds`, y la pista
                // resultante no la reproduce ni el propio Android. Antes de
                // escribir nada, mejor que lo resuelva quien sepa convertir.
                requireAudioSpecificConfig(audioFormat, audioFile)
            }

            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = audioFormat?.let { muxer.addTrack(it) } ?: NO_TRACK

            videoExtractor.selectTrack(videoTrack)
            if (audioTrack != NO_TRACK) audioExtractor.selectTrack(audioTrack)

            val sourceBytes = videoFile.length() + (audioFile?.length() ?: 0L)
            val copied = SamplesCopied(sourceBytes, expectsAudio = audioFile != null)
            Log.i(
                TAG,
                "Entradas: video ${videoFile.name} (${describe(videoExtractor, videoTrack)}), " +
                    "audio ${audioFile?.name} (${audioFile?.let { describe(audioExtractor, audioTrack) } ?: "no hay"})"
            )

            muxer.start()
            writing = true

            interleave(
                muxer = muxer,
                videoExtractor = videoExtractor,
                audioExtractor = audioExtractor,
                outputVideoTrack = outputVideoTrack,
                outputAudioTrack = outputAudioTrack,
                copied = copied,
                onProgress = onProgress
            )

            muxer.stop()
            writing = false

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw RemuxNotPossible("el muxer no escribio ninguna muestra")
            }
            if (!copied.hasSamplesOnEveryTrack()) {
                throw RemuxNotPossible(
                    "una pista se quedo sin muestras (${copied.summarize()}); " +
                        "mejor un archivo convertido que uno mudo"
                )
            }

            Log.i(
                TAG,
                "Remux sin recodificar: ${videoFile.name} + ${audioFile?.name} -> " +
                    "${outputFile.name}, ${outputFile.length()} B (origen $sourceBytes B); " +
                    "escritas ${copied.summarize()}"
            )
            done = true
            outputFile
        } catch (e: RemuxNotPossible) {
            throw e
        } catch (e: RuntimeException) {
            // El muxer se niega cuando el formato no cabe en MP4 o el movil no
            // soporta el codec. Es exactamente el caso que quiere la ruta de
            // conversion, no un error de descarga.
            throw RemuxNotPossible(e.message ?: e.javaClass.simpleName)
        } finally {
            // `stop()` sin haber escrito muestras deja un MP4 sin `moov`: no
            // vale ni como parcial, asi que solo se llama si se empezo a escribir.
            if (!done) {
                if (writing) runCatching { muxer.stop() }
                outputFile.delete()
            }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    /**
     * Escribe las muestras de las dos pistas en orden de tiempo.
     *
     * El bucle alterna entre las dos: el muxer va guardando lo que no puede
     * escribir todavia y espera a la otra pista, asi que volcar todo el video y
     * luego todo el audio agota su cola y la descarga se cuelga. Es un merge
     * clasico de dos listas ya ordenadas, que es lo que son las muestras de un
     * fichero.
     *
     * Cada pista se rebasa en su primer tiempo: en los ficheros de YouTube el
     * audio y el video no empiezan exactamente en el mismo instante, y sin
     * rebasar el muxer descarta muestras por escribir antes del inicio.
     */
    private suspend fun interleave(
        muxer: MediaMuxer,
        videoExtractor: MediaExtractor,
        audioExtractor: MediaExtractor,
        outputVideoTrack: Int,
        outputAudioTrack: Int,
        copied: SamplesCopied,
        onProgress: (Float) -> Unit
    ) {
        val videoBuffer = sampleBuffer(videoExtractor, VIDEO_MIME_PREFIX, MIN_VIDEO_SAMPLE_BUFFER)
        val audioBuffer = if (outputAudioTrack == NO_TRACK) {
            null
        } else {
            sampleBuffer(audioExtractor, AUDIO_MIME_PREFIX, MIN_AUDIO_SAMPLE_BUFFER)
        }

        var videoBase = UNSET_BASE
        var audioBase = UNSET_BASE
        var videoSamples = 0
        var audioSamples = 0
        var videoBytes = 0L
        var audioBytes = 0L
        var hasVideo = true
        var hasAudio = audioBuffer != null
        var videoTime = if (hasVideo) videoExtractor.sampleTime else NO_MORE_SAMPLES
        var audioTime = if (hasAudio) audioExtractor.sampleTime else NO_MORE_SAMPLES

        while (hasVideo || hasAudio) {
            // Una pausa o un cancelar durante el remux tienen que notarse: son
            // miles de muestras y puede tardar.
            coroutineContext.ensureActive()

            val takeVideo = hasVideo && (!hasAudio || videoTime <= audioTime)
            if (takeVideo) {
                val written = writeSample(
                    muxer, videoExtractor, videoBuffer, outputVideoTrack, videoBase
                ) { videoBase = it }
                if (written == null) {
                    hasVideo = false
                } else {
                    videoSamples += written.samples
                    videoBytes += written.bytes
                    videoTime = videoExtractor.sampleTime
                }
                copied.addVideo(videoSamples, videoBytes)
            } else {
                val written = writeSample(
                    muxer,
                    audioExtractor,
                    checkNotNull(audioBuffer),
                    outputAudioTrack,
                    audioBase
                ) { audioBase = it }
                if (written == null) {
                    hasAudio = false
                } else {
                    audioSamples += written.samples
                    audioBytes += written.bytes
                    audioTime = audioExtractor.sampleTime
                }
                copied.addAudio(audioSamples, audioBytes)
            }
            copied.report(onProgress)
        }
        copied.report(onProgress)
        onProgress(1f)
    }

    /** Una muestra escrita: cuantas y cuantos bytes. */
    private data class Written(val samples: Int, val bytes: Long)

    /**
     * Escribe la siguiente muestra de una pista y avanza.
     *
     * @param base tiempo del primer sample de la pista; si aun no se ha leido,
     *   es [UNSET_BASE] y [setBase] lo fija con el de ahora.
     * @return `null` si no quedan muestras.
     */
    private fun writeSample(
        muxer: MediaMuxer,
        extractor: MediaExtractor,
        buffer: ByteBuffer,
        outputTrack: Int,
        base: Long,
        setBase: (Long) -> Unit
    ): Written? {
        // El fin de pista se decide por `readSampleData`, **nunca** por
        // `sampleTime`. Las pistas de audio de MP4 llevan `elst` con el retardo
        // del encoder (`media_time=1024`), y Android lo descuenta: la primera
        // muestra sale con tiempo negativo (-23219 us en un m4a de 44,1 kHz).
        // Un tiempo negativo no es "no hay mas muestras", y tomarlo por el fin
        // es un MP4 con imagen y sin sonido.
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return null
        val sampleTime = extractor.sampleTime

        val start = if (base == UNSET_BASE) {
            setBase(sampleTime)
            sampleTime
        } else {
            base
        }
        // El muxer lee del buffer entre `position` y `limit`, no entre 0 y el
        // tamano de muestra: hay que marcarlos o escribe el buffer entero, con
        // los restos del sample anterior pegados detras.
        buffer.position(0)
        buffer.limit(size)

        val info = MediaCodec.BufferInfo()
        // Rebasado en el primer sample, el desfase de la edit list desaparece;
        // y el suelo en 0 porque el muxer rechaza escribir antes del inicio.
        info.set(0, size, (sampleTime - start).coerceAtLeast(0L), extractor.sampleFlags)
        muxer.writeSampleData(outputTrack, buffer, info)

        extractor.advance()
        return Written(samples = 1, bytes = size.toLong())
    }

    /**
     * El buffer del tamaño que pide la pista, con un suelo por debajo.
     *
     * **Directo, no heap**: `MediaExtractor.readSampleData` pasa el buffer a
     * codigo nativo, y un buffer de memoria de Java no tiene direccion que
     * darle. Con uno de esos devuelve -1 y la pista entera se pierde en
     * silencio: es un MP4 con imagen y sin sonido.
     */
    private fun sampleBuffer(
        extractor: MediaExtractor,
        mimePrefix: String,
        floor: Int
    ): ByteBuffer {
        val track = extractor.requireTrack(mimePrefix, null)
        val format = extractor.getTrackFormat(track)
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        return ByteBuffer.allocateDirect(maxOf(declared, floor))
    }

    /** El indice de la primera pista del tipo pedido, o la razon por la que no hay. */
    private fun MediaExtractor.requireTrack(mimePrefix: String, source: File?): Int {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        throw RemuxNotPossible(
            buildString {
                append("no hay ninguna pista ")
                append(if (mimePrefix == AUDIO_MIME_PREFIX) "de audio" else "de video")
                if (source != null) append(" en ${source.name}")
            }
        )
    }

    /**
     * El audio tiene que ser AAC con su `AudioSpecificConfig`.
     *
     * El contenedor y el codec los decide quien llama; esto solo se fija en el
     * dato que sin el cual el MP4 sale roto.
     */
    private fun requireAudioSpecificConfig(format: MediaFormat, source: File) {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val isAac = mime.startsWith("audio/mp4a") || mime.startsWith("audio/aac")
        if (isAac && format.getByteBuffer("csd-0") == null) {
            throw RemuxNotPossible("el audio de ${source.name} no trae csd-0")
        }
    }

    /** Bytes escritos, para medir el progreso sobre lo que se ha de leer. */
    private class SamplesCopied(
        private val total: Long,
        private val expectsAudio: Boolean
    ) {
        private var written = 0L
        private var lastReported = 0f
        private var videoSamples = 0
        private var videoBytes = 0L
        private var audioSamples = 0
        private var audioBytes = 0L

        /** Totales acumulados, nunca una lista: sumar acumulados cada vez que se
         *  avisa multiplicaria el recuento y daria cifras absurdas. */
        fun addVideo(samples: Int, bytes: Long) {
            videoSamples = samples
            videoBytes = bytes
            written = videoBytes + audioBytes
        }

        fun addAudio(samples: Int, bytes: Long) {
            audioSamples = samples
            audioBytes = bytes
            written = videoBytes + audioBytes
        }

        /**
         * Cuanto entro y cuanto salio de cada pista.
         *
         * Es la comprobacion que delata una perdida de muestras: si el muxer
         * escribe menos bytes de los que lei el extractor, el archivo sale con
         * imagen pero sin sonido, o con un salto en medio.
         */
        fun summarize(): String =
            "video $videoSamples muestras $videoBytes B, audio $audioSamples muestras $audioBytes B"

        /** Una pista que se quedo sin muestras no es un remux utilizable. */
        fun hasSamplesOnEveryTrack(): Boolean =
            videoSamples > 0 && (!expectsAudio || audioSamples > 0)

        /** No se avisa en cada muestra: son miles y el consumidor va mas lento. */
        fun report(onProgress: (Float) -> Unit) {
            if (total <= 0L) return
            val fraction = (written.toFloat() / total).coerceIn(0f, 1f)
            if (fraction - lastReported >= PROGRESS_STEP) {
                lastReported = fraction
                onProgress(fraction)
            }
        }
    }

    /** Lo que hay que saber de una pista para saber si el remux va a salir bien. */
    private fun describe(extractor: MediaExtractor, track: Int): String {
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            "${format.getLong(MediaFormat.KEY_DURATION) / 1000} ms"
        } else {
            "sin duracion"
        }
        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).toString()
        } else {
            "sin max-input-size"
        }
        return "${format.getString(MediaFormat.KEY_MIME)} en $durationUs, buffer $maxInput, " +
            "csd-0 ${if (format.getByteBuffer("csd-0") != null) "si" else "no"}, " +
            "${extractor.trackCount} pista(s) en el fichero"
    }

    private companion object {
        const val NO_TRACK = -1
        const val UNSET_BASE = Long.MIN_VALUE
        const val NO_MORE_SAMPLES = Long.MAX_VALUE
        const val VIDEO_MIME_PREFIX = "video/"
        const val AUDIO_MIME_PREFIX = "audio/"

        // Suelo del buffer de muestras. `KEY_MAX_INPUT_SIZE` no siempre viene, y
        // un frame de video puede pesar bastante mas de lo que el extractor
        // declara; quedarse corto pierde muestras.
        const val MIN_VIDEO_SAMPLE_BUFFER = 1024 * 1024
        const val MIN_AUDIO_SAMPLE_BUFFER = 256 * 1024

        const val PROGRESS_STEP = 0.01f
    }
}
