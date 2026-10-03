package com.elimd.downloader.core.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Un remux que copia las muestras tiene que cumplir una serie de cosas que un
 * test de "existe y tiene dos pistas" no mira, y cada una ha fallado de verdad:
 *
 * 1. **No se pierde ninguna muestra.** La edit list del audio (`media_time=1024`)
 *    hace que la primera muestra tenga tiempo negativo; si eso se toma por "fin
 *    de pista", el MP4 sale con imagen y mudo.
 * 2. **El formato no cambia.** Si el video sigue siendo `video/avc` con el mismo
 *    `csd-0`, no se ha recodificado: es el bug del archivo tres veces mas
 *    grande.
 * 3. **Las pistas se intercalan.** Volcar todo el video y luego todo el audio no
 *    produce un MP4 que se pueda reproducir de principio a fin.
 */
@RunWith(AndroidJUnit4::class)
class Mp4RemuxerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun asset(name: String): File {
        val out = File(temp.root, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    /** Lo que se lee de una pista: formato y las muestras que tiene. */
    private class Track(
        val mime: String,
        val format: MediaFormat,
        val samples: List<Long>,
        val bytes: Long
    )

    private fun readTrack(file: File, prefix: String): Track {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val index = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
        }
        val format = extractor.getTrackFormat(index)
        extractor.selectTrack(index)
        val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        val times = mutableListOf<Long>()
        var bytes = 0L
        while (true) {
            // El fin se decide por `readSampleData`, nunca por `sampleTime`: un
            // tiempo negativo es una edit list, no el final.
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            times += extractor.sampleTime
            bytes += size
            extractor.advance()
        }
        extractor.release()
        return Track(format.getString(MediaFormat.KEY_MIME).orEmpty(), format, times, bytes)
    }

    private fun remuxedVideoAndAudio(): Triple<File, File, File> {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_audio.m4a")
        val output = File(temp.root, "salida.mp4")
        runBlocking {
            MediaMuxer(context, Mp4Remuxer()).mux(video, audio, output, "video/mp4", "audio/mp4")
        }
        return Triple(video, audio, output)
    }

    @Test
    fun noSePierdeNingunaMuestra() {
        val (video, audio, output) = remuxedVideoAndAudio()

        val origenVideo = readTrack(video, "video/")
        val origenAudio = readTrack(audio, "audio/")
        val resultadoVideo = readTrack(output, "video/")
        val resultadoAudio = readTrack(output, "audio/")

        assertEquals("se han perdido muestras de video", origenVideo.samples.size, resultadoVideo.samples.size)
        assertEquals("se han perdido muestras de audio", origenAudio.samples.size, resultadoAudio.samples.size)
        assertEquals("los bytes de video no coinciden", origenVideo.bytes, resultadoVideo.bytes)
        assertEquals("los bytes de audio no coinciden", origenAudio.bytes, resultadoAudio.bytes)
    }

    @Test
    fun elFormatoDeCadaPistaNoCambia() {
        val (video, audio, output) = remuxedVideoAndAudio()

        assertEquals("el video cambio de formato", readTrack(video, "video/").mime, readTrack(output, "video/").mime)
        assertEquals("el audio cambio de formato", readTrack(audio, "audio/").mime, readTrack(output, "audio/").mime)
        // El `csd-0` es la cabecera del codec: si cambia, el video se decodifico
        // y se volvio a codificar, que es justo lo que multiplicaba el peso.
        assertTrue(
            "el csd-0 del video no es el del origen: el video se ha recodificado",
            readTrack(output, "video/").format.getByteBuffer("csd-0")
                ?.let { it == readTrack(video, "video/").format.getByteBuffer("csd-0") } == true
        )
    }

    @Test
    fun losTiemposEmpiezanEnCeroYSonCrecientes() {
        val (_, _, output) = remuxedVideoAndAudio()

        listOf("video/", "audio/").forEach { prefix ->
            val times = readTrack(output, prefix).samples
            assertTrue("la pista $prefix no tiene muestras", times.isNotEmpty())
            // El muxer rechaza escribir antes del inicio, y el rebasar en el
            // primer sample es lo que lo evita con la edit list.
            assertTrue("la pista $prefix empieza en ${times.first()}, no en 0 o mas", times.first() >= 0L)
            assertEquals(
                "los tiempos de $prefix no son crecientes",
                times.sorted(),
                times
            )
        }
    }

    /**
     * Las dos pistas van alternadas.
     *
     * Un MP4 con 3 segundos de video seguidos de 3 de audio se puede abrir, pero
     * no se puede empezar a mirar hasta que no se ha leido el video entero. Por
     * eso el bucle intercala, y por eso se comprueba que el hueco maximo entre
     * muestras no abarque una pista completa.
     */
    @Test
    fun lasPistasSeIntercalan() {
        val (_, _, output) = remuxedVideoAndAudio()

        val muestras = listOf("video/", "audio/")
            .flatMap { readTrack(output, it).samples }
            .sorted()
        val saltoMayor = muestras.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        // El sample de video mas largo dura ~40 ms; 200 ms es holgado para la
        // duracion del sample de audio y aun asi delata "todo video y luego
        // todo el audio".
        assertTrue("las pistas no se intercalan: hay un salto de ${saltoMayor} us", saltoMayor < 200_000L)
    }

    @Test
    fun sinAudioSoloCopiaElVideo() = runBlocking {
        val video = asset("mux_video.mp4")
        val output = File(temp.root, "solo.mp4")

        val result = Mp4Remuxer().remux(video, null, output)

        assertTrue(readTrack(result, "video/").samples.isNotEmpty())
    }

    /**
     * Si una pista no se puede leer, no se entrega un archivo mudo.
     *
     * El remux devuelve `RemuxNotPossible` y quien llama cae a la ruta de
     * convertir, que es mas lenta pero si tiene sonido.
     */
    @Test
    fun unVideoSinPistaDeAudioNoSeInventaSonido() = runBlocking {
        val video = asset("mux_video.mp4")
        val audio = asset("mux_video.mp4") // no tiene pista de audio
        val output = File(temp.root, "falso.mp4")

        val error = runCatching {
            Mp4Remuxer().remux(video, audio, output)
        }.exceptionOrNull()

        assertTrue("deberia avisar de que no se puede copiar: $error", error is RemuxNotPossible)
        assertTrue("no deberia quedar un archivo a medias", !output.exists() || output.length() == 0L)
    }
}
