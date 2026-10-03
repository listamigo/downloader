package com.elimd.downloader.feature.downloads

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elimd.downloader.core.download.formatBytes
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadStatus
import com.elimd.downloader.feature.main.MainViewModel
import java.io.File
import java.util.Locale

/**
 * Pantalla de gestion de descargas, alimentada por Room.
 */
@Composable
fun DownloadsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Descargas", style = MaterialTheme.typography.headlineSmall)
            if (downloads.any { it.status == DownloadStatus.DOWNLOADING }) {
                TextButton(onClick = viewModel::cancelAll) { Text("Cancelar todo") }
            }
        }

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hay descargas",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(downloads, key = { it.id }) { download ->
                    DownloadItem(
                        download = download,
                        onPlay = {
                            val file = completedFileFor(download)
                            if (file != null) {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, download.mimeType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }
                        },
                        onPause = { viewModel.pause(download.id) },
                        onResume = { viewModel.resume(download.id) },
                        onRetry = { viewModel.retry(download.id) },
                        onDelete = { viewModel.delete(download.id) }
                    )
                }
            }
        }
    }
}

/**
 * Resuelve el fichero final de una descarga completada.
 */
private fun completedFileFor(download: Download): File? {
    if (download.status != DownloadStatus.COMPLETED) return null
    val extension = if (download.downloadType.name == "AUDIO") "m4a" else "mp4"
    val file = File(download.filePath, "${download.fileName}.$extension")
    return file.takeIf { it.exists() }
}

@Composable
private fun DownloadItem(
    download: Download,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = download.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (download.status == DownloadStatus.DOWNLOADING ||
                download.status == DownloadStatus.PAUSED
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = download.progress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!download.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = download.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusLabel(download),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(download)
                )
                Row {
                    when (download.status) {
                        DownloadStatus.DOWNLOADING -> TextButton(onClick = onPause) { Text("Pausar") }
                        DownloadStatus.PAUSED -> TextButton(onClick = onResume) { Text("Reanudar") }
                        DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                            TextButton(onClick = onRetry) { Text("Reintentar") }
                        DownloadStatus.COMPLETED -> TextButton(onClick = onPlay) { Text("Reproducir") }
                        DownloadStatus.QUEUED -> Unit
                    }
                    TextButton(onClick = onDelete) { Text("Eliminar") }
                }
            }
        }
    }
}

/**
 * Una linea con todo lo que se sabe de la descarga en este instante.
 *
 * Durante la descarga: por donde va, cuanto pesa, cuanto queda y como se llama.
 * Al terminar, que es cuando de verdad interesa: el tamaño del fichero y la
 * calidad que se pidio, para comprobar de un vistazo que no ha salido otra.
 */
@Composable
private fun statusLabel(download: Download): String = when (download.status) {
    DownloadStatus.DOWNLOADING -> listOfNotNull(
        "${download.progress.toInt()}%",
        transferredLabel(download.downloadedBytes, download.totalBytes),
        // El contador solo aparece cuando el motor ya tiene una medida: en los
        // primeros segundos cualquier estimación es inventada.
        remainingLabel(download.eta),
        download.fileName
    ).joinToString(" · ")

    DownloadStatus.COMPLETED -> listOfNotNull(
        download.status.name,
        download.quality.takeIf { it.isNotBlank() },
        formatBytes(download.fileSize)
    ).joinToString(" · ")

    else -> download.status.name
}

/** "12,3 MB de 28,6 MB", o solo lo que se sepa si el total aun no se conoce. */
internal fun transferredLabel(downloaded: Long, total: Long, locale: Locale = Locale.getDefault()): String? {
    val downloadedLabel = formatBytes(downloaded, locale) ?: return null
    val totalLabel = formatBytes(total, locale) ?: return downloadedLabel
    return "$downloadedLabel de $totalLabel"
}

/** "quedan 45 s", "quedan 3 min"... o `null` si aun no se puede saber. */
internal fun remainingLabel(seconds: Long): String? = when {
    seconds <= 0L -> null
    seconds < 60L -> "quedan ${seconds}s"
    seconds < 3600L -> "quedan ${seconds / 60} min"
    else -> "quedan ${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

@Composable
private fun statusColor(download: Download) = when (download.status) {
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.secondary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}