package com.elimd.downloader.core.network

import android.content.Context
import android.util.Log
import com.elimd.downloader.core.common.sanitizeVideoId
import com.elimd.downloader.core.extract.MediaResolver
import com.elimd.downloader.data.source.YouTubeSearchDataSource
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.SearchResult
import com.elimd.downloader.domain.model.YouTubeVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adaptador entre la interfaz de dominio y el [MediaResolver].
 *
 * No contiene logica de extraccion: delega en el resolver activo, de modo que
 * cambiar de backend (extractor local o servidor con yt-dlp) no requiere tocar
 * esta capa.
 */
@Singleton
class YouTubeSearchDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaResolver: MediaResolver,
    private val httpClient: OkHttpClient
) : YouTubeSearchDataSource {

    override suspend fun searchVideos(query: String, pageToken: String?): SearchResult =
        mediaResolver.search(query, pageToken)

    override suspend fun getVideoInfo(videoId: String): YouTubeVideo? =
        mediaResolver.getVideoInfo(videoId)

    override suspend fun getAvailableQualities(videoId: String): List<DownloadQuality> =
        mediaResolver.getAvailableQualities(videoId)

    override suspend fun getRelatedVideos(videoId: String, limit: Int): List<YouTubeVideo> =
        mediaResolver.getRelatedVideos(videoId, limit)

    /**
     * Las miniaturas de YouTube son imagenes estaticas publicas, asi que se
     * descargan directamente por HTTP sin pasar por el extractor.
     */
    override suspend fun downloadThumbnail(videoId: String, quality: String): String? =
        withContext(Dispatchers.IO) {
            // El id viene de la red y acaba siendo nombre de fichero y URL: si
            // no tiene forma de id de YouTube no se usa tal cual.
            val safeId = sanitizeVideoId(videoId)
            if (safeId == null) {
                Log.w(TAG, "videoId con forma inesperada: se omite la miniatura")
                return@withContext null
            }
            try {
                val name = when (quality) {
                    "maxres" -> "maxresdefault"
                    "sd" -> "sddefault"
                    else -> "hqdefault"
                }
                val targetDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
                val target = File(targetDir, "$safeId.jpg")

                if (target.exists() && target.length() > 0) return@withContext target.absolutePath

                val url = "https://i.ytimg.com/vi/$safeId/$name.jpg"
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.isEmpty()) return@withContext null
                    target.writeBytes(bytes)
                    target.absolutePath
                }
            } catch (e: Exception) {
                // Una miniatura es cosmética: sin imagen no pasa nada, pero el
                // fallo queda registrado. Antes el runCatching se tragaba
                // cualquier Throwable — tambien un OutOfMemoryError de
                // body.bytes() — sin ni siquiera log.
                Log.w(TAG, "No se pudo descargar la miniatura de $safeId", e)
                null
            }
        }

    override suspend fun getVideoFormats(videoId: String): List<DownloadQuality> =
        getAvailableQualities(videoId)
}

private const val TAG = "YouTubeSearch"
