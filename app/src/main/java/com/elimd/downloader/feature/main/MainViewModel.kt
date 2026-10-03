package com.elimd.downloader.feature.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elimd.downloader.domain.model.AppSettings
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.domain.model.Download
import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType
import com.elimd.downloader.domain.model.YouTubeVideo
import com.elimd.downloader.domain.usecase.CancelAllDownloadsUseCase
import com.elimd.downloader.domain.usecase.CancelDownloadUseCase
import com.elimd.downloader.domain.usecase.DeleteDownloadUseCase
import com.elimd.downloader.domain.usecase.GetAllDownloadsUseCase
import com.elimd.downloader.domain.usecase.GetQualitiesUseCase
import com.elimd.downloader.domain.usecase.GetSettingsFlowUseCase
import com.elimd.downloader.domain.usecase.PauseDownloadUseCase
import com.elimd.downloader.domain.usecase.ResumeDownloadUseCase
import com.elimd.downloader.domain.usecase.RetryDownloadUseCase
import com.elimd.downloader.domain.usecase.SearchYouTubeUseCase
import com.elimd.downloader.domain.usecase.StartDownloadUseCase
import com.elimd.downloader.domain.usecase.UpdateAutoPlayThumbnailsUseCase
import com.elimd.downloader.domain.usecase.UpdateBackgroundAudioUseCase
import com.elimd.downloader.domain.usecase.UpdateDownloadQualityUseCase
import com.elimd.downloader.domain.usecase.UpdateLanguageUseCase
import com.elimd.downloader.domain.usecase.UpdateDownloadTypeUseCase
import com.elimd.downloader.domain.usecase.UpdateMaxConcurrentDownloadsUseCase
import com.elimd.downloader.domain.usecase.UpdateShowNotificationsUseCase
import com.elimd.downloader.domain.usecase.UpdateThemeUseCase
import com.elimd.downloader.domain.usecase.UpdateWallpaperEnabledUseCase
import com.elimd.downloader.domain.usecase.UpdateWallpaperSourceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la UI principal.
 */
data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val isSystemDark: Boolean = false,
    val selectedTab: Int = 0,
    val isLoading: Boolean = true,

    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val results: List<YouTubeVideo> = emptyList(),
    val nextPageToken: String? = null,
    val hasNextPage: Boolean = false,
    val searchError: String? = null,

    val selectedDownloadType: DownloadType = DownloadType.BOTH,
    val quality: DownloadQuality = DEFAULT_QUALITY,

    /**
     * Video cuyo selector de calidad esta abierto. Es `null` cuando el selector
     * esta cerrado, y las calidades de [qualityTarget] viven en [qualities].
     */
    val qualityTarget: YouTubeVideo? = null,
    val qualities: List<DownloadQuality> = emptyList(),
    val isLoadingQualities: Boolean = false,
    val qualityError: String? = null,

    val message: String? = null
)

/**
 * ViewModel de la aplicacion. Coordina busqueda, descargas y ajustes.
 */
    @HiltViewModel
class MainViewModel @Inject constructor(
    private val searchYouTube: SearchYouTubeUseCase,
    private val getQualities: GetQualitiesUseCase,
    private val startDownload: StartDownloadUseCase,

    private val pauseDownload: PauseDownloadUseCase,
    private val resumeDownload: ResumeDownloadUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val cancelAllDownloads: CancelAllDownloadsUseCase,
    private val retryDownload: RetryDownloadUseCase,
    private val deleteDownload: DeleteDownloadUseCase,
    private val getAllDownloads: GetAllDownloadsUseCase,
    private val getSettingsFlow: GetSettingsFlowUseCase,
    private val updateTheme: UpdateThemeUseCase,
    private val updateLanguage: UpdateLanguageUseCase,
    private val updateDownloadQuality: UpdateDownloadQualityUseCase,
    private val updateDownloadType: UpdateDownloadTypeUseCase,
    private val updateAutoPlayThumbnails: UpdateAutoPlayThumbnailsUseCase,
    private val updateShowNotifications: UpdateShowNotificationsUseCase,
    private val updateBackgroundAudio: UpdateBackgroundAudioUseCase,
    private val updateMaxConcurrentDownloads: UpdateMaxConcurrentDownloadsUseCase,
    private val updateWallpaperEnabled: UpdateWallpaperEnabledUseCase,
    private val updateWallpaperSource: UpdateWallpaperSourceUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeSettings()
        observeDownloads()
    }

    private fun observeSettings() {
        getSettingsFlow()
            .onEach { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        isLoading = false
                    )
                }
            }
            .catch { _uiState.update { state -> state.copy(isLoading = false) } }
            .launchIn(viewModelScope)
    }

    private fun observeDownloads() {
        getAllDownloads()
            .onEach { list -> _downloads.value = list }
            .catch { }
            .launchIn(viewModelScope)
    }

    // ---------------- Busqueda ----------------

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun search(query: String = _uiState.value.searchQuery) {
        if (query.isBlank() || _uiState.value.isSearching) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    searchQuery = query,
                    isSearching = true,
                    searchError = null,
                    nextPageToken = null,
                    hasNextPage = false
                )
            }
            runCatching { searchYouTube(query, null) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            results = result.videos,
                            nextPageToken = result.nextPageToken,
                            hasNextPage = result.hasNextPage
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchError = error.message ?: "Error al buscar"
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val token = state.nextPageToken
        if (!state.hasNextPage || token.isNullOrBlank() || state.isLoadingMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            runCatching { searchYouTube(state.searchQuery, token) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            results = it.results + result.videos,
                            nextPageToken = result.nextPageToken,
                            hasNextPage = result.hasNextPage
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingMore = false, searchError = error.message)
                    }
                }
        }
    }

    // ---------------- Descargas ----------------

    /**
     * Abre el selector de calidad de un video.
     *
     * Las calidades se piden al resolver y no vienen con la busqueda: son datos
     * del detalle del video, que es justo lo que YouTube bloquea por IP. Por eso
     * el fallo se muestra en el propio selector en vez de desaparecer.
     */
    fun openQualitySelector(video: YouTubeVideo) {
        _uiState.update {
            it.copy(
                qualityTarget = video,
                qualities = emptyList(),
                isLoadingQualities = true,
                qualityError = null
            )
        }
        viewModelScope.launch {
            runCatching { getQualities(video.videoId) }
                .onSuccess { list ->
                    _uiState.update { state ->
                        if (state.qualityTarget?.videoId != video.videoId) {
                            state // el usuario ya cambio de video o cerro el selector
                        } else {
                            state.copy(
                                qualities = list,
                                isLoadingQualities = false,
                                qualityError = if (list.isEmpty()) "El video no expone calidades" else null
                            )
                        }
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "No se pudieron obtener las calidades de ${video.videoId}", error)
                    _uiState.update { state ->
                        if (state.qualityTarget?.videoId != video.videoId) {
                            state
                        } else {
                            state.copy(
                                isLoadingQualities = false,
                                qualityError = error.message ?: "No se pudieron obtener las calidades"
                            )
                        }
                    }
                }
        }
    }

    fun closeQualitySelector() {
        _uiState.update {
            it.copy(
                qualityTarget = null,
                qualities = emptyList(),
                isLoadingQualities = false,
                qualityError = null
            )
        }
    }

    fun download(video: YouTubeVideo, type: DownloadType = _uiState.value.selectedDownloadType) {
        download(video, _uiState.value.quality, type)
    }

    fun download(video: YouTubeVideo, quality: DownloadQuality, type: DownloadType) {
        closeQualitySelector()
        viewModelScope.launch {
            runCatching { startDownload(video.videoId, quality, type) }
                .onSuccess { download ->
                    Log.i(TAG, "Descarga iniciada: ${download.id} ${download.title} (${quality.label})")
                    // A la lista de descargas, no a los resultados de la busqueda:
                    // es donde esta el progreso, y es lo que el usuario quiere ver
                    // en cuanto pulsa descargar.
                    _uiState.update {
                        it.copy(message = "Descargando: ${download.title}", selectedTab = TAB_DOWNLOADS)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Fallo al iniciar la descarga de ${video.videoId}", error)
                    _uiState.update {
                        it.copy(message = "Error: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
        }
    }

    fun setDownloadType(type: DownloadType) {
        _uiState.update { it.copy(selectedDownloadType = type) }
        viewModelScope.launch { updateDownloadType(type) }
    }

    fun pause(id: Long) = viewModelScope.launch { pauseDownload(id) }

    fun resume(id: Long) = viewModelScope.launch { resumeDownload(id) }

    fun retry(id: Long) = viewModelScope.launch { retryDownload(id) }

    fun cancel(id: Long) = viewModelScope.launch { cancelDownload(id) }

    fun cancelAll() = viewModelScope.launch { cancelAllDownloads() }

    fun delete(id: Long) = viewModelScope.launch { deleteDownload(id) }

    // ---------------- Navegacion y ajustes ----------------

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setSystemDark(isDark: Boolean) {
        _uiState.update { it.copy(isSystemDark = isDark) }
    }

    fun setTheme(theme: AppTheme) = viewModelScope.launch { updateTheme(theme) }

    fun setLanguage(language: String) = viewModelScope.launch { updateLanguage(language) }

    fun setDownloadQuality(quality: String) =
        viewModelScope.launch { updateDownloadQuality(quality) }

    fun setAutoPlayThumbnails(enabled: Boolean) =
        viewModelScope.launch { updateAutoPlayThumbnails(enabled) }

    fun setShowNotifications(enabled: Boolean) =
        viewModelScope.launch { updateShowNotifications(enabled) }

    fun setBackgroundAudio(enabled: Boolean) =
        viewModelScope.launch { updateBackgroundAudio(enabled) }

    fun setMaxConcurrentDownloads(count: Int) =
        viewModelScope.launch { updateMaxConcurrentDownloads(count) }

    fun setWallpaperEnabled(enabled: Boolean) =
        viewModelScope.launch { updateWallpaperEnabled(enabled) }

    fun setWallpaperSource(source: String) =
        viewModelScope.launch { updateWallpaperSource(source) }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

private const val TAG = "MainViewModel"

/** Pestanas de la barra inferior, en el mismo orden en que se pintan. */
const val TAB_HOME = 0
const val TAB_DOWNLOADS = 1
const val TAB_SETTINGS = 2

/**
 * Calidad por defecto: la mas alta que exista. El resolver la traduce a los
 * streams concretos de cada video, asi que no fija ninguna altura.
 */
private val DEFAULT_QUALITY = DownloadQuality(
    id = "best",
    label = "Mejor",
    format = "mp4"
)
