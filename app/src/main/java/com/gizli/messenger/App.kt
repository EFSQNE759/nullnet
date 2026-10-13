package com.gizli.messenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.gizli.messenger.data.Repo

class App : Application() {

    companion object {
        const val CHANNEL_ID = "gizli_poll"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Mesaj kontrolü", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Yeni mesajları arka planda kontrol eder"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        Repo.init(this)
    }
}
