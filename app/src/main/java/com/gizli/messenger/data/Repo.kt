package com.gizli.messenger.data

import android.content.Context
import com.gizli.messenger.BuildConfig
import com.gizli.messenger.crypto.Crypto
import com.gizli.messenger.crypto.SecureStore
import com.gizli.messenger.net.B64
import com.gizli.messenger.net.Envelope
import com.gizli.messenger.net.MqttTransport
import com.gizli.messenger.net.RelayClient
import com.gizli.messenger.net.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Uygulamanın beyni: anonim kimlik, kişiler, mesajlar ve şifreli yerel depolama.
 * Tüm hassas veriler cihazda SecureStore ile şifreli tutulur.
 */
object Repo {

    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay = RelayClient()

    private var mqtt: MqttTransport? = null
    private var mqttConfigKey: String? = null

    private data class OutboxItem(val contact: Contact, val msgId: String, val body: String)
    private val outbox = java.util.Collections.synchronizedList(ArrayList<OutboxItem>())

    /** Arka plan servisi yeni mesaj geldiğinde bildirim gönderebilsin diye. */
    var onNewMessage: ((Int) -> Unit)? = null

    private val _ready = MutableStateFlow(false)
    private val _identity = MutableStateFlow<Identity?>(null)
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val _settings = MutableStateFlow<Settings?>(null)

    val ready: StateFlow<Boolean> = _ready
    val identity: StateFlow<Identity?> = _identity
    val contacts: StateFlow<List<Contact>> = _contacts
    val messages: StateFlow<Map<String, List<Message>>> = _messages
    val settings: StateFlow<Settings?> = _settings

    private val idFile get() = File(ctx.filesDir, "identity.json.enc")
    private val contactsFile get() = File(ctx.filesDir, "contacts.json.enc")
    private val messagesFile get() = File(ctx.filesDir, "messages.json.enc")
    private val settingsFile get() = File(ctx.filesDir, "settings.json.enc")

    fun init(context: Context) {
        if (_ready.value) return
        ctx = context.applicationContext
        _identity.value = SecureStore.readSecureString(idFile)?.let(::parseIdentity) ?: newIdentity()
        _settings.value = SecureStore.readSecureString(settingsFile)?.let(::parseSettings) ?: defaultSettings()
        _contacts.value = SecureStore.readSecureString(contactsFile)?.let(::parseContacts) ?: emptyList()
        _messages.value = SecureStore.readSecureString(messagesFile)?.let(::parseMessages) ?: emptyMap()
        persistIdentity()
        persistSettings()
        _ready.value = true
    }

    // ---------------- Kimlik ----------------

    private fun newIdentity(): Identity {
        val (priv, pub) = Crypto.generateKeyPair()
        return Identity(
            userId = Crypto.newUserId(),
            encPublic = B64.enc(pub),
            encPrivate = B64.enc(priv),
            displayName = ""
        )
    }

    fun privateKey(): ByteArray = B64.dec(_identity.value!!.encPrivate)
    fun publicKey(): ByteArray = B64.dec(_identity.value!!.encPublic)

    fun setDisplayName(name: String) {
        _identity.value = _identity.value!!.copy(displayName = name)
        _settings.value = _settings.value!!.copy(displayName = name)
        persistIdentity(); persistSettings()
    }

    private fun persistIdentity() {
        val i = _identity.value ?: return
        val o = JSONObject()
            .put("userId", i.userId)
            .put("encPublic", i.encPublic)
            .put("encPrivate", i.encPrivate)
            .put("displayName", i.displayName)
        SecureStore.writeSecureString(idFile, o.toString())
    }

    private fun parseIdentity(s: String): Identity {
        val o = JSONObject(s)
        return Identity(
            o.getString("userId"),
            o.getString("encPublic"),
            o.getString("encPrivate"),
            o.optString("displayName", "")
        )
    }

    // ---------------- Ayarlar ----------------

    private fun defaultSettings() = Settings(
        relay = BuildConfig.DEFAULT_RELAY,
        displayName = _identity.value?.displayName ?: "",
        backgroundPolling = true,
        transport = "mqtt",
        mqttHost = "broker.hivemq.com",
        mqttPort = 8883,
        mqttTls = true
    )

    fun setRelay(url: String) {
        _settings.value = _settings.value!!.copy(relay = url.trim())
        persistSettings()
    }

    fun setTransport(kind: String) {
        _settings.value = _settings.value!!.copy(transport = kind)
        persistSettings()
    }

    fun setMqtt(host: String, port: Int, tls: Boolean) {
        _settings.value = _settings.value!!.copy(mqttHost = host.trim(), mqttPort = port, mqttTls = tls)
        persistSettings()
    }

    fun setBackgroundPolling(enabled: Boolean) {
        _settings.value = _settings.value!!.copy(backgroundPolling = enabled)
        persistSettings()
    }

    private fun persistSettings() {
        val s = _settings.value ?: return
        val o = JSONObject()
            .put("relay", s.relay)
            .put("displayName", s.displayName)
            .put("backgroundPolling", s.backgroundPolling)
            .put("transport", s.transport)
            .put("mqttHost", s.mqttHost)
            .put("mqttPort", s.mqttPort)
            .put("mqttTls", s.mqttTls)
        SecureStore.writeSecureString(settingsFile, o.toString())
    }

    private fun parseSettings(s: String): Settings {
        val o = JSONObject(s)
        return Settings(
            o.optString("relay", BuildConfig.DEFAULT_RELAY),
            o.optString("displayName", ""),
            o.optBoolean("backgroundPolling", true),
            o.optString("transport", "mqtt"),
            o.optString("mqttHost", "broker.hivemq.com"),
            o.optInt("mqttPort", 8883),
            o.optBoolean("mqttTls", true)
        )
    }

    // ---------------- Kişiler ----------------

    fun myInvite(): String {
        val i = _identity.value!!
        val s = _settings.value!!
        val o = JSONObject()
            .put("u", i.userId)
            .put("k", i.encPublic)
            .put("r", s.relay)
            .put("n", i.displayName)
        return "GIZLI1." + B64.encUrl(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** Dönen değer: contactId veya hata mesajı. */
    fun addContactFromInvite(text: String): Result<String> {
        return try {
            val raw = text.trim()
            require(raw.startsWith("GIZLI1.")) { "Geçersiz davet kodu." }
            val json = String(B64.decUrl(raw.removePrefix("GIZLI1.")), Charsets.UTF_8)
            val o = JSONObject(json)
            val userId = o.getString("u")
            if (userId == _identity.value!!.userId) return Result.failure(Exception("Bu sizin kendi davetiniz."))
            val contact = Contact(
                userId = userId,
                publicKey = o.getString("k"),
                name = o.optString("n", "Isimsiz"),
                relay = o.optString("r", _settings.value!!.relay),
                addedAt = System.currentTimeMillis(),
                pending = false
            )
            upsertContact(contact)
            sendHello(contact)
            Result.success(userId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun upsertContact(c: Contact) {
        val list = _contacts.value.toMutableList()
        val idx = list.indexOfFirst { it.userId == c.userId }
        if (idx >= 0) list[idx] = list[idx].copy(publicKey = c.publicKey, name = c.name, relay = c.relay, pending = false)
        else list.add(c)
        _contacts.value = list
        persistContacts()
    }

    private fun persistContacts() {
        val arr = JSONArray()
        for (c in _contacts.value) {
            arr.put(
                JSONObject()
                    .put("userId", c.userId)
                    .put("publicKey", c.publicKey)
                    .put("name", c.name)
                    .put("relay", c.relay)
                    .put("addedAt", c.addedAt)
                    .put("pending", c.pending)
            )
        }
        SecureStore.writeSecureString(contactsFile, arr.toString())
    }

    private fun parseContacts(s: String): List<Contact> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Contact(
                o.getString("userId"),
                o.getString("publicKey"),
                o.optString("name", "Isimsiz"),
                o.optString("relay", BuildConfig.DEFAULT_RELAY),
                o.optLong("addedAt", 0),
                o.optBoolean("pending", false)
            )
        }
    }

    fun contactById(id: String): Contact? = _contacts.value.firstOrNull { it.userId == id }

    fun removeContact(id: String) {
        _contacts.value = _contacts.value.filterNot { it.userId == id }
        _messages.value = _messages.value.toMutableMap().also { it.remove(id) }
        persistContacts(); persistMessages()
    }

    /** Tüm yerel verileri siler ve yeni bir anonim kimlik üretir. */
    fun wipeAll() {
        idFile.delete(); contactsFile.delete(); messagesFile.delete(); settingsFile.delete()
        _identity.value = newIdentity()
        _settings.value = defaultSettings()
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        persistIdentity(); persistSettings(); persistContacts(); persistMessages()
    }

    // ---------------- Mesajlar ----------------

    fun messagesFor(contactId: String): List<Message> = _messages.value[contactId].orEmpty()

    private fun appendMessage(m: Message) {
        val map = _messages.value.toMutableMap()
        val list = (map[m.contactId] ?: emptyList()).toMutableList()
        list.add(m)
        list.sortBy { it.ts }
        map[m.contactId] = list
        _messages.value = map
        persistMessages()
    }

    private fun updateStatus(id: String, status: MessageStatus) {
        val map = _messages.value.toMutableMap()
        val contactId = map.keys.firstOrNull { key -> map[key]!!.any { it.id == id } } ?: return
        map[contactId] = map[contactId]!!.map { if (it.id == id) it.copy(status = status) else it }
        _messages.value = map
        persistMessages()
    }

    private fun persistMessages() {
        val root = JSONObject()
        for ((contactId, list) in _messages.value) {
            val arr = JSONArray()
            for (m in list) {
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("contactId", m.contactId)
                        .put("outgoing", m.outgoing)
                        .put("text", m.text)
                        .put("ts", m.ts)
                        .put("status", m.status.name)
                )
            }
            root.put(contactId, arr)
        }
        SecureStore.writeSecureString(messagesFile, root.toString())
    }

    private fun parseMessages(s: String): Map<String, List<Message>> {
        val root = JSONObject(s)
        val out = HashMap<String, List<Message>>()
        for (contactId in root.keys()) {
            val arr = root.getJSONArray(contactId)
            out[contactId] = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Message(
                    o.getString("id"),
                    o.getString("contactId"),
                    o.getBoolean("outgoing"),
                    o.getString("text"),
                    o.getLong("ts"),
                    MessageStatus.valueOf(o.optString("status", "SENT"))
                )
            }
        }
        return out
    }

    fun sendMessage(contactId: String, text: String) {
        val contact = contactById(contactId) ?: return
        if (text.isBlank()) return
        val msgId = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        appendMessage(Message(msgId, contactId, true, text, ts, MessageStatus.PENDING))
        scope.launch {
            val inner = innerMessage(contact, text, ts, msgId)
            val isMqtt = _settings.value?.transport == "mqtt"

            var ok = transmit(contact, inner, Crypto.INFO_MSG)
            if (!ok && isMqtt) {
                // Bağlantı anlık kopmuş olabilir: kısa aralıklarla tekrar dene.
                for (i in 0 until 6) {
                    connectMqtt()
                    delay(2000)
                    ok = transmit(contact, inner, Crypto.INFO_MSG)
                    if (ok) break
                }
                if (!ok) {
                    // Hâlâ gitmedi: bağlantı gelince otomatik gönderilmek üzere kuyruğa al.
                    outbox.add(OutboxItem(contact, msgId, buildBody(contact, inner, Crypto.INFO_MSG)))
                    return@launch // durum PENDING kalır
                }
            }
            updateStatus(msgId, if (ok) MessageStatus.SENT else MessageStatus.FAILED)
        }
    }

    private fun innerMessage(contact: Contact, text: String, ts: Long, msgId: String): String {
        val i = _identity.value!!
        return JSONObject()
            .put("type", "msg")
            .put("from", i.userId)
            .put("name", i.displayName)
            .put("body", text)
            .put("ts", ts)
            .put("id", msgId)
            .toString()
    }

    private fun sendHello(contact: Contact) {
        scope.launch {
            val i = _identity.value!!
            val inner = JSONObject()
                .put("type", "hello")
                .put("from", i.userId)
                .put("name", i.displayName)
                .put("ts", System.currentTimeMillis())
                .toString()
            transmit(contact, inner, Crypto.INFO_HELLO)
        }
    }

    private fun buildBody(contact: Contact, innerJson: String, info: ByteArray): String {
        val sealed = Crypto.sealFor(privateKey(), B64.dec(contact.publicKey), innerJson.toByteArray(Charsets.UTF_8), info)
        val env = Envelope(
            to = contact.userId,
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            senderPub = publicKey(),
            sealed = sealed
        )
        return Wire.toJson(env)
    }

    private suspend fun transmit(contact: Contact, innerJson: String, info: ByteArray): Boolean {
        val body = buildBody(contact, innerJson, info)
        return if (_settings.value?.transport == "mqtt") {
            mqtt?.publish(topicFor(contact.userId), body) ?: false
        } else {
            relay.send(contact.relay, body)
        }
    }

    // ---------------- Taşıma (MQTT / relay) ----------------

    private fun topicFor(userId: String) = "gizli/v1/$userId"

    /** MQTT bağlantısını kurar. Yapılandırma değişmediyse mevcut bağlantıyı
     *  korur (yeniden kurmaz) — aksi halde oturum bozulur ve mesajlar düşer. */
    fun connectMqtt() {
        val s = _settings.value ?: return
        if (s.transport != "mqtt") return
        val me = _identity.value ?: return
        val key = "${s.mqttHost}:${s.mqttPort}:${s.mqttTls}:${me.userId}"
        val existing = mqtt
        if (existing != null && mqttConfigKey == key) {
            existing.ensureConnected()
            return
        }
        disconnectMqtt()
        val t = MqttTransport(
            host = s.mqttHost,
            port = s.mqttPort,
            tls = s.mqttTls,
            clientId = "gizli-" + me.userId,
            topic = topicFor(me.userId),
            onMessage = { raw -> if (processIncoming(raw)) onNewMessage?.invoke(1) },
            onConnected = { flushOutbox() }
        )
        mqtt = t
        mqttConfigKey = key
        t.connect()
    }

    fun disconnectMqtt() {
        mqtt?.close()
        mqtt = null
        mqttConfigKey = null
    }

    /** MQTT yeniden bağlanınca bekleyen mesajları gönderir. */
    private fun flushOutbox() {
        val pending = outbox.toList()
        if (pending.isEmpty()) return
        scope.launch {
            for (item in pending) {
                if (mqtt?.publish(topicFor(item.contact.userId), item.body) == true) {
                    outbox.remove(item)
                    updateStatus(item.msgId, MessageStatus.SENT)
                }
            }
        }
    }

    // ---------------- Gelen kutusu ----------------

    /** Tek bir zarfı çözer ve işler; yeni mesaj ise true. */
    fun processIncoming(raw: String): Boolean {
        return try {
            val env = Wire.fromJson(raw)
            val info = Crypto.INFO_MSG
            val plain = try {
                Crypto.openFrom(privateKey(), env.senderPub, env.sealed, info)
            } catch (e: Exception) {
                Crypto.openFrom(privateKey(), env.senderPub, env.sealed, Crypto.INFO_HELLO)
            }
            handlePlain(env, String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            false
        }
    }

    /** Sunucudan yeni mesajları çeker ve işler; yeni mesaj sayısını döndürür. (Relay modu) */
    suspend fun pollOnce(): Int {
        val s = _settings.value ?: return 0
        val me = _identity.value ?: return 0
        val envelopes = relay.pull(s.relay, me.userId)
        var newCount = 0
        for (raw in envelopes) {
            if (processIncoming(raw)) newCount++
        }
        return newCount
    }

    private fun handlePlain(env: Envelope, plainJson: String): Boolean {
        val o = JSONObject(plainJson)
        val from = o.optString("from", "")
        val name = o.optString("name", "Isimsiz")
        val senderPubB64 = B64.enc(env.senderPub)
        if (from.isEmpty()) return false

        val existing = contactById(from)
        if (existing == null) {
            upsertContact(
                Contact(
                    userId = from,
                    publicKey = senderPubB64,
                    name = name.ifBlank { "Isimsiz" },
                    relay = _settings.value!!.relay,
                    addedAt = System.currentTimeMillis(),
                    pending = false
                )
            )
        }

        val type = o.optString("type", "msg")
        if (type == "msg") {
            val msgId = o.optString("id", UUID.randomUUID().toString())
            if (messagesFor(from).any { it.id == msgId }) return false
            appendMessage(Message(msgId, from, false, o.optString("body", ""), o.optLong("ts", System.currentTimeMillis()), MessageStatus.DELIVERED))
            return true
        }
        // hello: yeni kişi eklendi, mesaj sayılmaz
        return existing == null
    }
}
