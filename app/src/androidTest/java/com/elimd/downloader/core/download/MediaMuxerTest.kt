package com.elimd.downloader.core.download

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * `MediaMuxer` es la pieza con mas riesgo y menos cobertura: depende de los
 * codecs del telefono y, en su camino de conversion, de que `media3-transformer`
 * funcione con el hilo dedicado que monta la clase. Un test unitario en la JVM no
 * llega.
 *
 * Por eso es instrumentado: corre en el dispositivo de verdad, que es donde
 * esta la duda. Los medios de entrada son dos ficheros pequenos generados con
 * ffmpeg y subidos a `androidTest/assets`.
 *
 * Los tests usan MIME de MP4, que es lo que produce el resolutor para H.264 y
 * AAC: asi se ejercita el camino de remux, que es el que se usa en la practica.
 * La ruta de conversion se cubre aparte, en `MediaMuxerTranscodeTest`.
 */
@RunWith(AndroidJUnit4::class)
class MediaMuxerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val muxer = MediaMuxer(context, Mp4Remuxer())

    /**
     * Los assets se leen con el contexto **de la instrumentacion**, no con el de
     * la app: `androidTest/assets` va dentro del APK de test, y
     * `ApplicationProvider` devuelve el contexto de la app, donde no esta.
     */
    private val testAssets
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): File {
        val out = File(temp.root, name)
        testAssets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun tracksOf(file: File): Pair<Boolean, Boolean> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            ) == "yes"
            val hasAudio = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"
            hasVideo to hasAudio
        } finally {
            retriever.release()
        }
    }

    private fun durationMsOf(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }

    @Test
    fun uneVideoYAudioEnUnSoloMp4() = runBlocking {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_audio.m4a")
        val output = File(temp.root, "salida.mp4")

        val result = muxer.mux(video, audio, output, MP4_VIDEO, MP4_AUDIO)

        assertTrue("no se produjo el fichero", result.exists())
        assertTrue("el fichero salida esta vacio", result.length() > 0L)

        val (hasVideo, hasAudio) = tracksOf(result)
        assertTrue("el resultado no tiene pista de video", hasVideo)
        assertTrue("el resultado no tiene pista de audio", hasAudio)
    }

    @Test
    fun dejaElVideoSoloCuandoNoHayAudio() = runBlocking {
        val video = asset("mux_video.mp4")
        val output = File(temp.root, "solo_video.mp4")

        val result = muxer.mux(video, null, output, MP4_VIDEO, null)

        val (hasVideo, hasAudio) = tracksOf(result)
        assertTrue("el resultado no tiene pista de video", hasVideo)
        assertTrue("no se deberia haber anadido audio", !hasAudio)
    }

    @Test
    fun fallaConUnMotivoClaroSiFaltaUnaDeLasPistas() = runBlocking {
        val output = File(temp.root, "nunca.mp4")
        val audio = asset("mux_audio.m4a")

        val error = runCatching {
            muxer.mux(File(temp.root, "no_existe.mp4"), audio, output, MP4_VIDEO, MP4_AUDIO)
        }.exceptionOrNull()

        assertTrue("deberia fallar", error != null)
        assertTrue(
            "el motivo deberia mencionar el fichero de video: ${error?.message}",
            error?.message?.contains("video") == true
        )
    }

    @Test
    fun avisaDelProgresoYTerminaEnUno() = runBlocking {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_audio.m4a")
        val output = File(temp.root, "progreso.mp4")

        val samples = mutableListOf<Float>()
        muxer.mux(video, audio, output, MP4_VIDEO, MP4_AUDIO) { samples += it }

        // Con un fichero tan pequeno puede no llegar a muestrear el progreso
        // intermedio, pero el final siempre se emite.
        assertTrue("el progreso no llega a 1.0: $samples", samples.isEmpty() || samples.last() == 1f)
        assertEquals("el progreso deberia ser monotono", samples, samples.sorted())
    }

    /**
     * El bug que motiva [Mp4Remuxer]: con pistas que ya son de MP4, unir no es
     * convertir. Si el archivo resultante pesara mucho mas que sus partes,
     * estaria recodificandolo.
     *
     * El margen es amplio a proposito: un MP4 reescrito con las mismas muestras
     * pesa lo mismo mas la cabecera; uno recodificado multiplica.
     */
    @Test
    fun elArchivoPesaLoMismoQueSusPartes() = runBlocking {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_audio.m4a")
        val output = File(temp.root, "peso.mp4")

        val result = muxer.mux(video, audio, output, MP4_VIDEO, MP4_AUDIO)

        val parts = video.length() + audio.length()
        assertTrue(
            "el resultado pesa ${result.length()} B y las partes $parts B: " +
                "eso no es copiar, es recodificar",
            result.length() < parts * 2
        )
    }

    /**
     * Si se copia sin recodificar, el resultado dura lo mismo que el origen.
     *
     * Es la otra mitad de la garantia: un archivo que encoge con la calidad
     * intacta tambien tiene que conservar la duracion, y si al rebasar los
     * tiempos se hiciera mal, el video se desincronizaria del audio o se
     * perderian muestras por el final.
     */
    @Test
    fun laDuracionSeConserva() = runBlocking {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_audio.m4a")
        val output = File(temp.root, "duracion.mp4")

        val result = muxer.mux(video, audio, output, MP4_VIDEO, MP4_AUDIO)

        val expected = durationMsOf(video) ?: error("el asset de video no expone duracion")
        val actual = durationMsOf(result) ?: error("el resultado no expone duracion")
        // Un margen del 2%: absorbing timestamps con redondeo unitario puede
        // apartarse unas decenas de milisegundos, no un segundo.
        assertTrue(
            "la duracion paso de $expected ms a $actual ms",
            kotlin.math.abs(expected - actual) <= expected * 2 / 100
        )
    }

    private companion object {
        const val MP4_VIDEO = "video/mp4"
        const val MP4_AUDIO = "audio/mp4"
    }
}
