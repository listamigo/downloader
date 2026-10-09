package com.elimd.downloader.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elimd.downloader.BuildConfig
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.feature.main.MainViewModel

/**
 * Pantalla de configuración.
 * Permite configurar tema, idioma, descargas, notificaciones, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val qualityDialogOptions = listOf("best", "1080p", "720p", "480p", "360p")
    val typeDialogOptions = listOf("BOTH", "VIDEO", "AUDIO")
    val qualityDialogIndex = remember(settings.downloadQuality) {
        qualityDialogOptions.indexOf(settings.downloadQuality).coerceAtLeast(0)
    }
    val typeDialogIndex = remember(settings.downloadType) {
        typeDialogOptions.indexOf(settings.downloadType.name).coerceAtLeast(0)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Configuración",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Tema
        item {
            SettingsSection(title = "Tema") {
                ThemeSelector(
                    currentTheme = settings.theme,
                    onThemeSelected = viewModel::setTheme
                )
            }
        }

        // Descargas
        item {
            SettingsSection(title = "Descargas") {
                SettingsItem(
                    title = "Calidad de descarga",
                    subtitle = settings.downloadQuality,
                    onClick = { viewModel.setDownloadQuality(qualityDialogOptions[(qualityDialogIndex + 1) % qualityDialogOptions.size]) }
                )
                SettingsItem(
                    title = "Tipo de descarga",
                    subtitle = settings.downloadType.name,
                    onClick = { viewModel.setDownloadType(com.elimd.downloader.domain.model.DownloadType.valueOf(typeDialogOptions[(typeDialogIndex + 1) % typeDialogOptions.size])) }
                )
                SettingsItem(
                    title = "Descargas concurrentes máximas",
                    subtitle = settings.maxConcurrentDownloads.toString(),
                    onClick = { viewModel.setMaxConcurrentDownloads(if (uiState.settings.maxConcurrentDownloads >= 5) 1 else uiState.settings.maxConcurrentDownloads + 1) }
                )
            }
        }

        // Notificaciones
        item {
            SettingsSection(title = "Notificaciones") {
                SwitchItem(
                    title = "Mostrar notificaciones",
                    checked = settings.showNotifications,
                    onCheckedChange = viewModel::setShowNotifications
                )
                SwitchItem(
                    title = "Audio en segundo plano",
                    checked = settings.enableBackgroundAudio,
                    onCheckedChange = viewModel::setBackgroundAudio
                )
            }
        }

        // Avanzado
        item {
            SettingsSection(title = "Avanzado") {
                SwitchItem(
                    title = "Reproducir miniaturas automáticamente",
                    checked = settings.autoPlayThumbnails,
                    onCheckedChange = viewModel::setAutoPlayThumbnails
                )
                SwitchItem(
                    title = "Habilitar fondo de pantalla",
                    checked = settings.wallpaperEnabled,
                    onCheckedChange = viewModel::setWallpaperEnabled
                )
                SettingsItem(
                    title = "Fuente de fondo de pantalla",
                    subtitle = settings.wallpaperSource,
                    onClick = { viewModel.setWallpaperSource(if (settings.wallpaperSource == "downloads") "thumbnail" else "downloads") },
                    enabled = settings.wallpaperEnabled
                )
            }
        }

        // Servidor (plan B, ADR-022)
        item {
            SettingsSection(title = "Servidor remoto") {
                SwitchItem(
                    title = "Usar servidor remoto",
                    checked = settings.useRemoteServer,
                    onCheckedChange = viewModel::setUseRemoteServer
                )
                Text(
                    text = "Descarga a través de un backend propio con yt-dlp en lugar " +
                        "del extractor local. Déjalo apagado si no tienes un servidor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                ServerUrlField(
                    url = settings.serverUrl,
                    onUrlChange = viewModel::setServerUrl
                )
            }
        }

        // Información
        item {
            SettingsSection(title = "Información") {
                SettingsItem(
                    title = "Versión de la aplicación",
                    subtitle = BuildConfig.VERSION_NAME
                )
                SettingsItem(
                    title = "Acerca de",
                    subtitle = "elimd downloader"
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    // Sin onClick la fila es informativa: sin ripple ni apariencia pulsable.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            color = Color.Transparent
        ) { row() }
    } else {
        Surface(color = Color.Transparent) { row() }
    }
}

@Composable
fun SwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun ServerUrlField(
    url: String,
    onUrlChange: (String) -> Unit
) {
    // El valor vive en un estado local para que escribir no dependa del ritmo
    // con que DataStore reemite el flow; cada tecla se persiste igual.
    var input by remember(url) { mutableStateOf(url) }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            onUrlChange(it)
        },
        label = { Text("URL del servidor") },
        placeholder = { Text("https://tu-servidor.up.railway.app") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelector(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    val themes = AppTheme.values()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        themes.forEach { theme ->
            FilterChip(
                selected = theme == currentTheme,
                onClick = { onThemeSelected(theme) },
                label = { Text(theme.name) }
            )
        }
    }
}
