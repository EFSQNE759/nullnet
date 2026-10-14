package com.gizli.messenger.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gizli.messenger.App
import com.gizli.messenger.MainActivity
import com.gizli.messenger.R
import com.gizli.messenger.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Arka planda mesajları yoklar. FCM/Google servisi KULLANMAZ: bu, gizliliği korur,
 * karşılığında pil tüketimini artırır. Ayarlardan kapatılabilir.
 */
class PollService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val NEW_MSG_ID = 1002
        const val INTERVAL_MS = 15000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PollService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PollService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, ongoingNotification(), type)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(applicationContext)
        scope.launch {
            Repo.onNewMessage = { n -> notifyNewMessages(n) }
            // Depolama yüklenene kadar bekle (init artık arka planda çalışıyor).
            var waited = 0
            while (isActive && !Repo.ready.value && waited < 15000) { delay(200); waited += 200 }
            if (Repo.settings.value?.transport == "mqtt") {
                // Hazır broker: bağlantıyı sürekli açık tut
                Repo.connectMqtt()
                while (isActive) {
                    delay(60000)
                    Repo.connectMqtt()
                }
            } else {
                while (isActive) {
                    try {
                        Repo.pollOnce()
                    } catch (_: Exception) {
                    }
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("nullnet çalışıyor")
            .setContentText("Yeni mesajlar için kontrol ediliyor")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyNewMessages(count: Int) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("Yeni mesaj")
            .setContentText("$count yeni mesajınız var")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NEW_MSG_ID, n)
    }
}
