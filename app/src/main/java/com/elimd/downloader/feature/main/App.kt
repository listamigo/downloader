package com.elimd.downloader.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elimd.downloader.R
import com.elimd.downloader.domain.model.AppTheme
import com.elimd.downloader.feature.downloads.DownloadsScreen
import com.elimd.downloader.feature.home.HomeScreen
import com.elimd.downloader.feature.settings.SettingsScreen

/**
 * Aplicación principal de elimd downloader.
 * Configura el tema (claro/oscuro/AMOLED) y la navegacion inferior.
 */
@Composable
fun ElimdDownloaderApp(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val colorScheme = when (uiState.settings.theme) {
        AppTheme.LIGHT -> LightColorScheme
        AppTheme.DARK -> DarkColorScheme
        AppTheme.SYSTEM -> if (uiState.isSystemDark) DarkColorScheme else LightColorScheme
        AppTheme.AMOLED -> DarkColorScheme.copy(
            background = Color.Black,
            surface = Color.Black
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        val snackbarHostState = remember { SnackbarHostState() }

        // Sin esto, los errores de descarga se tragan: el ViewModel los guarda
        // en el estado pero nunca se muestran al usuario.
        LaunchedEffect(uiState.message) {
            uiState.message?.let { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.consumeMessage()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Inicio"
                            )
                        },
                        label = { Text("Home") },
                        selected = uiState.selectedTab == 0,
                        onClick = { viewModel.selectTab(0) }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = "Descargas"
                            )
                        },
                        label = { Text("Descargas") },
                        selected = uiState.selectedTab == 1,
                        onClick = { viewModel.selectTab(1) }
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Configuración"
                            )
                        },
                        label = { Text("Configuración") },
                        selected = uiState.selectedTab == 2,
                        onClick = { viewModel.selectTab(2) }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (uiState.selectedTab) {
                    0 -> HomeScreen(viewModel)
                    1 -> DownloadsScreen(viewModel)
                    else -> SettingsScreen(viewModel)
                }
            }
        }
    }
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFFF620E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B3),
    onPrimaryContainer = Color(0xFF3D1D00),
    secondary = Color(0xFFFF9100),
    onSecondary = Color.White,
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFE),
    onSurface = Color(0xFF212121),
    error = Color(0xFFFF5252)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB68A),
    onPrimary = Color(0xFF542100),
    primaryContainer = Color(0xFF773300),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFFFB871),
    onSecondary = Color(0xFF4A2800),
    background = Color(0xFF1C1B1B),
    onBackground = Color(0xFFE5E2E2),
    surface = Color(0xFF211F1F),
    onSurface = Color(0xFFE5E2E2),
    error = Color(0xFFFFB4AB)
)
