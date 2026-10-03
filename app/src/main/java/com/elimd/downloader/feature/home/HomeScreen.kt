package com.elimd.downloader.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.feature.main.MainViewModel

/**
 * Pantalla de busqueda de videos con resultados paginados.
 *
 * Pulsar "Descargar" no baja nada directamente: abre el selector de calidad,
 * porque la calidad no se conoce hasta que se resuelve el detalle del video.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Dispara la paginacion al llegar cerca del final de la lista.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.hasNextPage) viewModel.loadMore()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Buscar en YouTube") },
            // El boton del teclado (Enter o "Buscar") hace lo mismo que el
            // boton de al lado: buscar. Sin esto hay que apartar la mano del
            // teclado para pulsar.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.search()
                }
            ),
            trailingIcon = {
                TextButton(
                    onClick = { viewModel.search() },
                    enabled = state.searchQuery.isNotBlank() && !state.isSearching
                ) {
                    Text("Buscar")
                }
            }
        )

        TypeSelector(
            selected = state.selectedDownloadType,
            onSelect = viewModel::setDownloadType
        )

        state.searchError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        when {
            state.isSearching -> CenterBox { CircularProgressIndicator() }

            state.results.isEmpty() -> EmptySearchState(hasQuery = state.searchQuery.isNotBlank())

            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.results, key = { it.videoId }) { video ->
                    VideoResultCard(
                        video = video,
                        onDownload = { viewModel.openQualitySelector(video) }
                    )
                }
                if (state.isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
                if (!state.hasNextPage) {
                    item {
                        Text(
                            text = "No hay mas resultados",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    state.qualityTarget?.let { target ->
        QualitySelectorSheet(
            video = target,
            qualities = state.qualities,
            selectedType = state.selectedDownloadType,
            selectedQuality = state.quality,
            isLoading = state.isLoadingQualities,
            error = state.qualityError,
            onTypeChange = viewModel::setDownloadType,
            onPick = { quality -> viewModel.download(target, quality, state.selectedDownloadType) },
            onDismiss = viewModel::closeQualitySelector
        )
    }
}

/**
 * Hoja con las calidades que el video expone de verdad.
 *
 * La lista se pide al resolver en el momento de abrirla: no se puede quitar de
 * la busqueda porque las calidades vienen del detalle del video, que es
 * precisamente lo que YouTube bloquea segun la IP. Si ese detalle falla, el
 * error se muestra aqui en vez de dejar al usuario pulsando en silencio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySelectorSheet(
    video: YouTubeVideo,
    qualities: List<DownloadQuality>,
    selectedType: DownloadType,
    selectedQuality: DownloadQuality,
    isLoading: Boolean,
    error: String?,
    onTypeChange: (DownloadType) -> Unit,
    onPick: (DownloadQuality) -> Unit,
    onDismiss: () -> Unit
) {
    // En "solo audio" solo tiene sentido lo que es audio, y al reves. La opcion
    // "Mejor" es de video, asi que tambien queda fuera en ese caso.
    val options = remember(qualities, selectedType) {
        if (selectedType == DownloadType.AUDIO) {
            qualities.filter { it.isAudioOnly }
        } else {
            qualities.filter { !it.isAudioOnly }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            TypeSelector(selected = selectedType, onSelect = onTypeChange)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> QualityError(error = error, onDismiss = onDismiss)

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (options.isEmpty()) {
                        Text(
                            text = "Este video no expone calidades para ese tipo de descarga",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    options.forEach { quality ->
                        QualityRow(
                            quality = quality,
                            isSelected = quality.id == selectedQuality.id,
                            onClick = { onPick(quality) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QualityRow(
    quality: DownloadQuality,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = quality.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            val details = qualityDetails(quality)
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}

/** Segunda linea del selector: fps, contenedor y si obliga a unir las pistas. */
private fun qualityDetails(quality: DownloadQuality): String = buildList {
    if (quality.isBest) add("máxima disponible")
    quality.fps?.let { add("$it fps") }
    add(quality.format.uppercase())
    // Por encima de 720p no hay un stream con audio: se baja por separado y se
    // unen, asi que conviene que el coste extra sea visible antes de pulsar.
    if (quality.requiresMuxing) add("une audio y video")
}.joinToString(" · ")

@Composable
private fun QualityError(error: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Si reaparece, comprueba que el fichero de cookies sigue en su sitio " +
                "y que sus cookies no han caducado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDismiss) { Text("Cerrar") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: DownloadType,
    onSelect: (DownloadType) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            DownloadType.BOTH to "Video + audio",
            DownloadType.VIDEO to "Solo video",
            DownloadType.AUDIO to "Solo audio"
        ).forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun VideoResultCard(
    video: YouTubeVideo,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(120.dp)
                        .height(68.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (video.duration.isNotBlank()) {
                        Text(
                            text = video.duration,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Descargar")
            }
        }
    }
}

@Composable
private fun EmptySearchState(hasQuery: Boolean) {
    CenterBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hasQuery) "Sin resultados" else "Busca videos de YouTube",
                style = MaterialTheme.typography.bodyLarge
            )
            if (!hasQuery) {
                Text(
                    text = "Escribe arriba y pulsa Buscar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}
