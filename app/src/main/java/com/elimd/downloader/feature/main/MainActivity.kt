package com.elimd.downloader.feature.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * Actividad principal de elimd downloader.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationPermissionIfNeeded()

        setContent {
            val systemDark = isSystemInDarkTheme()
            ElimdDownloaderAppWrapper(systemDark = systemDark)
        }
    }

    /**
     * Las notificaciones requieren permiso explicito desde Android 13 (API 33).
     */
    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ElimdDownloaderAppWrapper(systemDark: Boolean) {
    val viewModel: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    androidx.compose.runtime.LaunchedEffect(systemDark) {
        viewModel.setSystemDark(systemDark)
    }
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ElimdDownloaderApp(viewModel)
        }
    }
}