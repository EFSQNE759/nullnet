package com.gizli.messenger.net

import android.util.Base64
import com.gizli.messenger.crypto.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object B64 {
    fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun encUrl(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun decUrl(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}

/** Sunucuda dolaşan şifreli zarf. Sunucu içeriği okuyamaz. */
data class Envelope(
    val to: String,
    val id: String,
    val ts: Long,
    val senderPub: ByteArray,
    val sealed: Crypto.Sealed
)

object Wire {
    fun toJson(e: Envelope): String {
        val o = JSONObject()
        o.put("to", e.to)
        o.put("id", e.id)
        o.put("ts", e.ts)
        o.put("spk", B64.enc(e.senderPub))
        o.put("epk", B64.enc(e.sealed.ephemeralPub))
        o.put("s", B64.enc(e.sealed.salt))
        o.put("n", B64.enc(e.sealed.nonce))
        o.put("c", B64.enc(e.sealed.ciphertext))
        return o.toString()
    }

    fun fromJson(s: String): Envelope {
        val o = JSONObject(s)
        return Envelope(
            to = o.getString("to"),
            id = o.optString("id", Crypto.newUserId()),
            ts = o.optLong("ts", System.currentTimeMillis()),
            senderPub = B64.dec(o.getString("spk")),
            sealed = Crypto.Sealed(
                ephemeralPub = B64.dec(o.getString("epk")),
                salt = B64.dec(o.getString("s")),
                nonce = B64.dec(o.getString("n")),
                ciphertext = B64.dec(o.getString("c"))
            )
        )
    }
}

class RelayClient {

    fun send(relay: String, envelopeJson: String, proxy: java.net.Proxy? = null): Boolean {
        return try {
            val url = URL(relay.trimEnd('/') + "/send")
            val conn = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(envelopeJson.toByteArray(Charsets.UTF_8)) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun pull(relay: String, userId: String, proxy: java.net.Proxy? = null): List<String> {
        return try {
            val u = relay.trimEnd('/') + "/pull?u=" + URLEncoder.encode(userId, "UTF-8")
            val url = URL(u)
            val conn = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                if (conn.responseCode !in 200..299) return emptyList()
                val text = conn.inputStream.bufferedReader().readText()
                if (text.isBlank()) return emptyList()
                val arr = JSONArray(text)
                (0 until arr.length()).map { arr.getJSONObject(it).toString() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
