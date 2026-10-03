package com.elimd.downloader.core.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Descarga bytes de una URL directa a un fichero local.
 *
 * Es el equivalente en Kotlin de lo que hacia el subproceso de yt-dlp, pero sin
 * depender de un binario externo: YouTube ya devuelve las URLs firmadas y solo
 * hace falta transferirlas.
 *
 * La transferencia va **por segmentos y en paralelo**. El cuello de botella casi
 * nunca es el ancho de banda del movil, sino la conexion sola: con una sola
 * conexion, 13 MB tardaron tres minutos (unos 80 KB/s). Con cuatro conexiones a
 * la vez, el mismo fichero baja en una fraccion. Es lo que hacen los gestores de
 * descarga, y por eso un proxy suele parecer mas rapido aunque solo cambie la IP.
 */
@Singleton
class HttpFileDownloader @Inject constructor(
    private val client: OkHttpClient
) {

    /**
     * Descarga [url] a [target], en paralelo si el servidor admite rangos.
     *
     * Reanudable en los dos caminos. Con segmentos hace falta ademas el registro
     * que acompaña al parcial (ver [sidecarFor]): el destino mide su tamaño
     * total desde el principio, asi que el tamaño del fichero ya no dice por
     * donde seguir.
     *
     * @param onProgress se invoca con (bytesDescargados, bytesTotales).
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        val remote = probe(url)
        if (remote == null || !remote.supportsRanges) {
            // Sin rangos no hay reparto posible: una sola conexion, como siempre.
            return@withContext downloadSequentially(
                url = url,
                target = target,
                knownTotal = remote?.totalBytes ?: 0L,
                onProgress = onProgress
            )
        }

        val total = remote.totalBytes
        if (target.exists() && target.length() == total) {
            // El parcial ya estaba entero. Reanudar algo terminado no es un fallo.
            sidecarFor(target).delete()
            onProgress(total, total)
            return@withContext target
        }

        downloadInSegments(url, target, total, onProgress)
    }

    /**
     * Cuanto mide el fichero y si el servidor sabe servir rangos.
     *
     * Se pregunta con `Range: bytes=0-0`: un solo byte, pero la respuesta trae el
     * total en `Content-Range`. Si el servidor contesta 200 con el fichero
     * entero, es que ignora los rangos y no hay particion posible.
     */
    private fun probe(url: String): RemoteFile? {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        return client.newCall(request).execute().use { response ->
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { it > 0 }

            when {
                total == null -> null
                response.code == HTTP_PARTIAL_CONTENT -> RemoteFile(total, supportsRanges = true)
                else -> RemoteFile(total, supportsRanges = false)
            }
        }
    }

    /**
     * Reparte lo que falta en varios tramos y los baja a la vez.
     *
     * El destino se reserva a su tamaño completo antes de escribir: cada
     * segmento escribe en su offset, y sin reservar, el ultimo en terminar
     * dejaria el final del fichero a medio hacer.
     */
    private suspend fun downloadInSegments(
        url: String,
        target: File,
        total: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File {
        val sidecar = sidecarFor(target)
        val completed = completedRanges(target, sidecar, total)
        val finished = CopyOnWriteArrayList(completed)
        val done = AtomicLong(completed.sumOf { it.size })

        val segments = planSegments(
            missing = missingRanges(completed, total),
            maxSegments = MAX_SEGMENTS,
            minSegmentBytes = MIN_SEGMENT_BYTES
        )
        if (segments.isEmpty()) {
            sidecar.delete()
            onProgress(done.get(), total)
            return target
        }

        val report = ProgressReporter(onProgress, total)
        onProgress(done.get(), total)
        RandomAccessFile(target, "rw").use { it.setLength(total) }

        try {
            coroutineScope {
                segments.map { segment ->
                    async(Dispatchers.IO) {
                        downloadSegment(url, target, segment) { written ->
                            report.publish(done.addAndGet(written))
                        }
                        finished.add(segment)
                        writeSidecar(sidecar, finished)
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            // El registro ya escrito es lo que permite continuar sin volver a
            // pagar los bytes que si llegaron, asi que se conserva a proposito.
            writeSidecar(sidecar, finished)
            throw e
        }

        sidecar.delete()
        check(target.length() == total) {
            "El fichero se ha quedado en ${target.length()} de $total bytes"
        }
        onProgress(total, total)
        return target
    }

    /**
     * Un tramo concreto: pide exactamente esos bytes y los escribe en su sitio.
     *
     * Cada segmento abre su propio descriptor sobre el mismo fichero; escribir
     * en offsets distintos no se pisa, que es justo por lo que se reparte.
     *
     * @param onWritten recibe los bytes **de este trozo recien escritos**, no el
     * total del tramo: quien lleva la cuenta los suma, y sumar un acumulado dos
     * veces dispara el progreso (que es como se llego a mostrar 2,1 GB de un
     * archivo de 14 MB).
     */
    private suspend fun downloadSegment(
        url: String,
        target: File,
        segment: Range,
        onWritten: suspend (justWritten: Long) -> Unit
    ) {
        val header = "bytes=${segment.start}-${segment.endInclusive}"
        val request = Request.Builder().url(url).header("Range", header).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != HTTP_PARTIAL_CONTENT) {
                throw IOException("HTTP ${response.code} en el tramo $header de $url")
            }
            // Un 200 con el fichero entero, cuando solo se ha pedido un tramo,
            // significa que el servidor no ha hecho caso: escribirlo entero
            // partiria el contenido en la posicion equivocada.
            if (response.code != HTTP_PARTIAL_CONTENT && segment.start != 0L) {
                throw IOException("El servidor ignora Range y devolvio el fichero entero para $header")
            }

            val body = response.body ?: throw IOException("Respuesta vacia en el tramo $header de $url")

            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(segment.start)
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                body.byteStream().use { input ->
                    var read = input.read(buffer)
                    while (read != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buffer, 0, read)
                        written += read
                        if (written > segment.size) {
                            throw IOException("El tramo $header ha devuelto mas bytes de los pedidos")
                        }
                        onWritten(read.toLong())
                        read = input.read(buffer)
                    }
                }
                if (written != segment.size) {
                    throw IOException("El tramo $header se ha quedado en $written de ${segment.size} bytes")
                }
            }
        }
    }

    /**
     * Reserva para los servidores que no admiten rangos: una conexion, escribiendo
     * en orden y sin huecos.
     *
     * Es el unico camino que se reanuda solo con el tamaño del fichero, porque lo
     * que hay por debajo del final es siempre bueno.
     */
    private suspend fun downloadSequentially(
        url: String,
        target: File,
        knownTotal: Long,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File {
        val existingBytes = if (target.exists()) target.length() else 0L
        val builder = Request.Builder().url(url)

        if (existingBytes > 0) {
            builder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(builder.build()).execute().use { response ->
            val appending = existingBytes > 0 && response.code == HTTP_PARTIAL_CONTENT

            // 416 con un fichero ya presente significa que se pide un Range por
            // encima de lo que hay, o sea que el fichero esta completo: no es un
            // fallo, es el objetivo cumplido. Sin esto, reanudar una descarga que
            // ya habia terminado los bytes fallaba y marcaba FAILED.
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE && existingBytes > 0) {
                onProgress(existingBytes, existingBytes)
                return target
            }

            val body = response.body ?: throw IOException("Respuesta vacia de $url")

            if (!response.isSuccessful && response.code != HTTP_PARTIAL_CONTENT) {
                throw IOException("HTTP ${response.code} al descargar $url")
            }

            val totalBytes = body.contentLength().takeIf { it > 0 }
                ?.plus(if (appending) existingBytes else 0L)
                ?: if (knownTotal > 0) knownTotal else existingBytes

            if (!appending && existingBytes > 0) {
                target.delete()
            }

            val report = ProgressReporter(onProgress, totalBytes)
            var downloaded = if (appending) existingBytes else 0L
            onProgress(downloaded, totalBytes)

            RandomAccessFile(target, "rw").use { raf ->
                if (appending) raf.seek(existingBytes) else raf.setLength(0)

                val buffer = ByteArray(BUFFER_SIZE)
                body.byteStream().use { input ->
                    var read = input.read(buffer)
                    while (read != -1) {
                        coroutineContext.ensureActive()
                        raf.write(buffer, 0, read)
                        downloaded += read
                        report.publish(downloaded)
                        read = input.read(buffer)
                    }
                }
            }
        }

        if (!target.exists() || target.length() == 0L) {
            throw IOException("La descarga de $url termino sin datos")
        }
        return target
    }

    /**
     * Los tramos ya completos.
     *
     * Sin registro se supone lo de siempre: los primeros [length] bytes son
     * buenos. Es lo correcto para los parciales que dejo cualquier version
     * anterior, que no sabia nada de segmentos.
     */
    private fun completedRanges(target: File, sidecar: File, total: Long): List<Range> {
        if (sidecar.exists()) {
            val recorded = parseRanges(sidecar.readText()).filter { it.start < total }
            if (recorded.isNotEmpty()) return recorded
        }
        val length = if (target.exists()) target.length() else 0L
        return if (length in 1 until total) listOf(Range(0, length - 1)) else emptyList()
    }

    private fun writeSidecar(sidecar: File, ranges: List<Range>) {
        sidecar.writeText(serializeRanges(ranges))
    }

    /** El registro va al lado del parcial, con su propio nombre. */
    private fun sidecarFor(target: File) = File(target.parentFile, "${target.name}$SEGMENTS_SUFFIX")

    /**
     * Borra el fichero parcial de una descarga reanudable.
     *
     * Se borra tambien el registro de segmentos: si se quedara, la siguiente
     * descarga creeria que tiene bytes que en realidad no estan.
     */
    fun discardPartial(target: File) {
        sidecarFor(target).delete()
        if (target.exists() && target.length() > 0) {
            target.delete()
        }
    }

    private data class RemoteFile(val totalBytes: Long, val supportsRanges: Boolean)

    /**
     * El progreso no se avisa en cada trozo de 64 KB: son cientos por segundo y
     * cada aviso actualiza un StateFlow y escribe una linea de log. Con cuatro
     * segmentos encima, cuatro veces mas.
     */
    private class ProgressReporter(
        private val onProgress: suspend (downloaded: Long, total: Long) -> Unit,
        private val total: Long
    ) {
        private var lastAt = 0L

        suspend fun publish(downloaded: Long) {
            val now = System.nanoTime()
            if (now - lastAt < PROGRESS_THROTTLE_NS) return
            lastAt = now
            onProgress(downloaded, total)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SEGMENTS_SUFFIX = ".segments"

        /**
         * Peticiones simultaneas por fichero. Cuatro es donde la conexion deja de
         * ser el cuello sin empezar a abusar del servidor ni a comerse la bateria.
         */
        private const val MAX_SEGMENTS = 4

        /** Por debajo de esto, repartir el fichero no ahorra tiempo. */
        private const val MIN_SEGMENT_BYTES = 2L * 1024 * 1024

        private const val PROGRESS_THROTTLE_NS = 150_000_000L
    }
}