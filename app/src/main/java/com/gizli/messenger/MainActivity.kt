package com.gizli.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gizli.messenger.data.Repo
import com.gizli.messenger.ui.GizliApp

class MainActivity : ComponentActivity() {

    private var backgroundedAt = 0L

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GizliApp() }
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        // Uygulama kilitliyse veya 30 saniyeden uzun süre arka planda kaldıysa kilitle.
        if (backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > 30_000) {
            Repo.lock()
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = System.currentTimeMillis()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
