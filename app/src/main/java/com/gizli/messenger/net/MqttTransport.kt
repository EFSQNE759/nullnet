package com.gizli.messenger.net

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Hazır (public) bir MQTT broker üzerinden mesaj taşır.
 *
 * Sunucu kurmaya gerek yok: uygulama ücretsiz bir aktarma servisine bağlanır.
 * Mesajlar UÇTAN UCA şifreli olduğu için broker içeriği okuyamaz. Konu (topic)
 * kullanıcının rastgele 26 karakterlik kodudur; tahmin edilemez.
 *
 * Gizlilik notu: broker, IP ve "kim ne zaman bağlandı" bilgisini (metadata)
 * görebilir. İçeriği göremez.
 */
class MqttTransport(
    host: String,
    port: Int,
    tls: Boolean,
    clientId: String,
    private val topic: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    torHost: String? = null,
    torPort: Int = 9050
) {
    private val uri = (if (tls) "ssl://" else "tcp://") + host + ":" + port
    private val client = MqttAsyncClient(uri, clientId, MemoryPersistence())
    private val socketFactory: javax.net.SocketFactory? =
        if (torHost != null) {
            if (tls) Socks.Tls(torHost, torPort) else Socks.Plain(torHost, torPort)
        } else null

    @Volatile
    private var started = false

    /** Bağlantıyı yalnızca ilk kez kurar; kopma/kopulmada Paho'nun
     *  automaticReconnect'i devralır. Böylece oturum bozulmaz, gönderilmiş
     *  ama teslim edilmemiş mesajlar kaybolmaz. */
    fun connect() {
        if (started) return
        started = true
        try {
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    subscribe()
                    onConnected()
                }

                override fun connectionLost(cause: Throwable?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload ?: return
                    onMessage(String(payload, Charsets.UTF_8))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 20
                keepAliveInterval = 30
                maxInflight = 100
                socketFactory?.let { setSocketFactory(it) }
            }
            client.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = subscribe()
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    // İlk bağlantı başarısızsa otomatik yeniden deneme devreye girmez;
                    // bayrağı geri al ki bir sonraki connect() çağrısı tekrar denesin.
                    started = false
                }
            })
        } catch (_: Exception) {
            started = false
        }
    }

    /** Bağlı değilse ve hiç başlatılmadıysa bağlanmayı dener. */
    fun ensureConnected() {
        if (!client.isConnected && !started) connect()
    }

    private fun subscribe() {
        try {
            if (client.isConnected) client.subscribe(topic, 1)
        } catch (_: Exception) {
        }
    }

    fun publish(topic: String, payload: String): Boolean {
        return try {
            if (!client.isConnected) return false
            val m = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            m.qos = 1
            client.publish(topic, m).waitForCompletion(10000)
            true
        } catch (_: Exception) {
            false
        }
    }

    val isConnected: Boolean
        get() = try {
            client.isConnected
        } catch (_: Exception) {
            false
        }

    fun close() {
        try {
            if (client.isConnected) client.disconnect(500)
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }
}
