package com.elimd.downloader.domain.model

/**
 * Modelo de dominio para un video de YouTube obtenido de la búsqueda.
 */
data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channelName: String,
    val duration: String,
    val viewCount: String,
    val uploadDate: String,
    val thumbnailUrl: String,
    val description: String? = null,
    val isLive: Boolean = false
)

/**
 * Calidad de descarga disponible.
 */
data class DownloadQuality(
    val id: String,
    val label: String,
    val format: String, // "mp4", "webm", "mp3", etc.
    val height: Int? = null, // null para audio
    val fileSize: Long? = null,
    val fps: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,

    /**
     * YouTube entrega video y audio juntos (progresivo) hasta ~720p. Por encima
     * de eso hay que bajar las dos pistas y unirlas, asi que la descarga necesita
     * un multiplexado extra. La UI lo avisa para que el coste sea visible.
     */
    val requiresMuxing: Boolean = false
) {
    /** Selector generico cuando el usuario no elige una calidad concreta. */
    val isBest: Boolean
        get() = !isAudioOnly && height == null
}

/**
 * Reconstruye la calidad de una descarga a partir de la etiqueta que se guardo
 * en la base de datos, para poder reintentarla sin volver a abrir el selector.
 *
 * Las etiquetas las genera el resolutor y solo tienen tres formas: "Mejor" (sin
 * altura), "1080p60" (altura y fps) y "720p" (solo altura). Una etiqueta de
 * audio ("160 kbps") no lleva altura: se devuelve como [DownloadType.AUDIO] lo
 * decide el tipo, aqui solo se marca como pista de audio.
 *
 * Una etiqueta que no encaje con ninguna de esas formas se resuelve como
 * "Mejor", que es la unica calidad que siempre existe: reintentar pidiendo mas
 * no es lo que se quiere, pero quedarse en cola sin hacer nada, si.
 */
fun qualityFromLabel(label: String): DownloadQuality {
    val text = label.trim()
    val resolution = RESOLUTION_LABEL.find(text)
    val height = resolution?.groupValues?.get(1)?.toIntOrNull()

    return when {
        height != null -> DownloadQuality(
            id = text,
            label = text,
            format = "mp4",
            height = height,
            fps = resolution.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
        )

        text.contains("kbps", ignoreCase = true) -> DownloadQuality(
            id = text,
            label = text,
            format = "m4a",
            isAudioOnly = true
        )

        else -> DownloadQuality(id = "best", label = "Mejor", format = "mp4")
    }
}

/** "1080p60", "1080p": la altura y, opcionalmente, los fps. */
private val RESOLUTION_LABEL = Regex("^(\\d+)p(\\d*)$")

/**
 * Tipo de descarga: audio, video o ambos.
 */
enum class DownloadType {
    AUDIO,
    VIDEO,
    BOTH
}

/**
 * Estado de una descarga.
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Entidad de descarga persistida.
 */
data class Download(
    val id: Long = 0,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val url: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val quality: String,
    val downloadType: DownloadType,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val speed: Long = 0L, // bytes por segundo
    val eta: Long = 0L, // segundos estimados
    val downloadedBytes: Long = 0L, // 0 = sin dato
    val totalBytes: Long = 0L, // 0 = sin dato
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

/**
 * Configuración de la aplicación.
 */
data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val language: String = "system",
    val downloadLocation: String = "default",
    val downloadQuality: String = "best",
    val downloadType: DownloadType = DownloadType.BOTH,
    val autoPlayThumbnails: Boolean = true,
    val showNotifications: Boolean = true,
    val enableBackgroundAudio: Boolean = true,
    val maxConcurrentDownloads: Int = 3,
    val wallpaperEnabled: Boolean = false,
    val wallpaperSource: String = "downloads", // "downloads", "thumbnail", "none"

    /**
     * Plan B (ADR-022): resolver metadatos y bajar bytes desde un servidor
     * propio con yt-dlp, en vez del extractor local. Apagado por defecto: sin
     * un servidor configurado la app funciona igual que siempre.
     */
    val useRemoteServer: Boolean = false,
    val serverUrl: String = ""
)

enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM,
    AMOLED
}

/**
 * Resultado de búsqueda de YouTube.
 */
data class SearchResult(
    val query: String,
    val videos: List<YouTubeVideo>,
    val nextPageToken: String? = null,
    val hasNextPage: Boolean = false
)

/**
 * Información del sistema para debugging.
 */
data class SystemInfo(
    val androidVersion: String,
    val appVersion: String,
    val availableStorage: Long,
    val totalStorage: Long,
    val networkType: String
)