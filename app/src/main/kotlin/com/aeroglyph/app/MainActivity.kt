package com.aeroglyph.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.aeroglyph.app.ui.navigation.AeroglyphRoot
import com.aeroglyph.app.ui.theme.AeroglyphTheme

class MainActivity : ComponentActivity() {

    private var permissionGranted by mutableStateOf(false)

    /**
     * True once Android stops showing the system prompt entirely -- at that
     * point the only way back is app settings, and the UI has to say so
     * instead of offering a button that silently does nothing.
     */
    private var permanentlyDenied by mutableStateOf(false)

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        permissionGranted = hasMicPermission()

        setContent {
            AeroglyphTheme {
                AeroglyphRoot(
                    permissionGranted = permissionGranted,
                    permanentlyDenied = permanentlyDenied,
                    onRequestPermission = { requestPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where the user granted it in Settings and came back.
        permissionGranted = hasMicPermission()
        if (permissionGranted) permanentlyDenied = false
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
