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
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    private val _settings = MutableStateFlow<Settings?>(null)
    private val _typing = MutableStateFlow<Set<String>>(emptySet())
    private val _locked = MutableStateFlow(false)

    val ready: StateFlow<Boolean> = _ready
    val identity: StateFlow<Identity?> = _identity
    val contacts: StateFlow<List<Contact>> = _contacts
    val messages: StateFlow<Map<String, List<Message>>> = _messages
    val groups: StateFlow<List<Group>> = _groups
    val settings: StateFlow<Settings?> = _settings
    val typing: StateFlow<Set<String>> = _typing
    val locked: StateFlow<Boolean> = _locked

    private val typingJobs = HashMap<String, kotlinx.coroutines.Job>()
    private val lastTypingSent = HashMap<String, Long>()
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var persistInFlight = false
    @Volatile
    private var persistPending = false

    private val idFile get() = File(ctx.filesDir, "identity.json.enc")
    private val contactsFile get() = File(ctx.filesDir, "contacts.json.enc")
    private val messagesFile get() = File(ctx.filesDir, "messages.json.enc")
    private val groupsFile get() = File(ctx.filesDir, "groups.json.enc")
    private val settingsFile get() = File(ctx.filesDir, "settings.json.enc")

    @Volatile
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        // Ağır Keystore okuma/yazma işlemleri ana iş parçacığını bloklamasın:
        // UI, kimlik hazır olana kadar yükleme ekranı gösterir (_ready / identity == null).
        scope.launch {
            val idStr = SecureStore.readSecureString(idFile)
            _identity.value = idStr?.let(::parseIdentity) ?: newIdentity()
            val setStr = SecureStore.readSecureString(settingsFile)
            _settings.value = setStr?.let(::parseSettings) ?: defaultSettings()
            _contacts.value = SecureStore.readSecureString(contactsFile)?.let(::parseContacts) ?: emptyList()
            _messages.value = SecureStore.readSecureString(messagesFile)?.let(::parseMessages) ?: emptyMap()
            _groups.value = SecureStore.readSecureString(groupsFile)?.let(::parseGroups) ?: emptyList()
            // Yalnızca ilk oluşturmada yaz (her açılışta gereksiz şifreleme/yazma yapma).
            if (idStr == null) persistIdentity()
            if (setStr == null) persistSettings()
            if (_settings.value?.lockEnabled == true) _locked.value = true
            _ready.value = true
        }
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

    fun setProfile(avatarB64: String, bio: String) {
        _identity.value = _identity.value!!.copy(avatarB64 = avatarB64, bio = bio)
        persistIdentity()
        broadcastProfile()
    }

    private fun persistIdentity() {
        val i = _identity.value ?: return
        val o = JSONObject()
            .put("userId", i.userId)
            .put("encPublic", i.encPublic)
            .put("encPrivate", i.encPrivate)
            .put("displayName", i.displayName)
            .put("avatarB64", i.avatarB64)
            .put("bio", i.bio)
        writeAsync(idFile, o.toString())
    }

    /** Ağır dosya yazma/işlemlerini ana iş parçacığından ayırır. */
    private fun writeAsync(file: File, text: String) {
        scope.launch { SecureStore.writeSecureString(file, text) }
    }

    private fun parseIdentity(s: String): Identity {
        val o = JSONObject(s)
        return Identity(
            o.getString("userId"),
            o.getString("encPublic"),
            o.getString("encPrivate"),
            o.optString("displayName", ""),
            o.optString("avatarB64", ""),
            o.optString("bio", "")
        )
    }

    /** Profil bilgisini (ad, avatar, bio) tüm kişilere bildirir. */
    private fun broadcastProfile() {
        val me = _identity.value ?: return
        scope.launch {
            val inner = JSONObject()
                .put("type", "profile")
                .put("from", me.userId)
                .put("name", me.displayName)
                .put("avatar", me.avatarB64)
                .put("bio", me.bio)
                .put("ts", System.currentTimeMillis())
                .toString()
            for (c in _contacts.value) {
                transmit(c, inner, Crypto.INFO_HELLO)
            }
        }
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

    fun setTheme(name: String) {
        _settings.value = _settings.value!!.copy(theme = name)
        persistSettings()
    }

    fun setSecureScreen(enabled: Boolean) {
        _settings.value = _settings.value!!.copy(secureScreen = enabled)
        persistSettings()
    }

    fun setTor(enabled: Boolean, host: String = "127.0.0.1", port: Int = 9050) {
        _settings.value = _settings.value!!.copy(useTor = enabled, torHost = host.trim(), torPort = port)
        persistSettings()
    }

    private fun proxyOrNull(): java.net.Proxy? {
        val s = _settings.value ?: return null
        return if (s.useTor) com.gizli.messenger.net.Socks.proxy(s.torHost.ifBlank { "127.0.0.1" }, s.torPort) else null
    }

    // ---------------- Uygulama kilidi ----------------

    private fun hashPin(pin: String, salt: String): String {
        // Yavaş KDF (PBKDF2) — PIN kaba kuvvetini zorlaştırır.
        val d = Crypto.deriveKey(pin, B64.dec(salt))
        return B64.enc(d)
    }

    fun setBiometric(enabled: Boolean) {
        _settings.value = _settings.value!!.copy(biometricEnabled = enabled)
        persistSettings()
    }

    fun setAutoUpdate(enabled: Boolean) {
        _settings.value = _settings.value!!.copy(autoUpdate = enabled)
        persistSettings()
    }

    /** pin=null ise kilidi kapatır. duressPin verilirse zorlama (duress) PIN'i de ayarlanır. */
    fun setLock(pin: String?, duressPin: String?) {
        val cur = _settings.value ?: return
        if (pin == null) {
            _settings.value = cur.copy(lockEnabled = false, pinHash = "", pinSalt = "", duressHash = "", duressSalt = "")
        } else {
            val salt = B64.enc(Crypto.randomBytes(16))
            val dSalt = B64.enc(Crypto.randomBytes(16))
            _settings.value = cur.copy(
                lockEnabled = true,
                pinSalt = salt,
                pinHash = hashPin(pin, salt),
                duressSalt = dSalt,
                duressHash = if (duressPin.isNullOrBlank()) "" else hashPin(duressPin, dSalt)
            )
        }
        persistSettings()
    }

    /** "ok", "duress" veya "wrong" döndürür. */
    fun verifyPin(pin: String): String {
        val s = _settings.value ?: return "wrong"
        if (!s.lockEnabled) return "ok"
        if (s.pinHash.isNotEmpty() && hashPin(pin, s.pinSalt) == s.pinHash) return "ok"
        if (s.duressHash.isNotEmpty() && hashPin(pin, s.duressSalt) == s.duressHash) return "duress"
        return "wrong"
    }

    fun lock() {
        if (_settings.value?.lockEnabled == true) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
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
            .put("theme", s.theme)
            .put("secureScreen", s.secureScreen)
            .put("useTor", s.useTor)
            .put("torHost", s.torHost)
            .put("torPort", s.torPort)
            .put("lockEnabled", s.lockEnabled)
            .put("pinHash", s.pinHash)
            .put("pinSalt", s.pinSalt)
            .put("duressHash", s.duressHash)
            .put("duressSalt", s.duressSalt)
            .put("biometricEnabled", s.biometricEnabled)
            .put("autoUpdate", s.autoUpdate)
        writeAsync(settingsFile, o.toString())
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
            o.optBoolean("mqttTls", true),
            o.optString("theme", "default"),
            o.optBoolean("secureScreen", true),
            o.optBoolean("useTor", false),
            o.optString("torHost", "127.0.0.1"),
            o.optInt("torPort", 9050),
            o.optBoolean("lockEnabled", false),
            o.optString("pinHash", ""),
            o.optString("pinSalt", ""),
            o.optString("duressHash", ""),
            o.optString("duressSalt", ""),
            o.optBoolean("biometricEnabled", false),
            o.optBoolean("autoUpdate", true)
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

    /** Gelen profil bilgisine göre kişinin ad/avatar/bio alanlarını günceller. */
    private fun updateContactProfile(userId: String, name: String, avatar: String?, bio: String?) {
        if (name.isBlank() && avatar == null && bio == null) return
        val list = _contacts.value.toMutableList()
        val idx = list.indexOfFirst { it.userId == userId }
        if (idx < 0) return
        val c = list[idx]
        list[idx] = c.copy(
            name = if (name.isNotBlank()) name else c.name,
            avatarB64 = avatar ?: c.avatarB64,
            bio = bio ?: c.bio
        )
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
                    .put("avatarB64", c.avatarB64)
                    .put("bio", c.bio)
                    .put("muted", c.muted)
            )
        }
        writeAsync(contactsFile, arr.toString())
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
                o.optBoolean("pending", false),
                o.optString("avatarB64", ""),
                o.optString("bio", ""),
                o.optBoolean("muted", false)
            )
        }
    }

    fun contactById(id: String): Contact? = _contacts.value.firstOrNull { it.userId == id }

    /** Bir sohbetteki okunmamış gelen mesaj sayısı. */
    fun unreadCount(chatId: String): Int =
        _messages.value[chatId].orEmpty().count { !it.outgoing && it.status != MessageStatus.READ }

    fun setContactMuted(id: String, muted: Boolean) {
        val list = _contacts.value.toMutableList()
        val idx = list.indexOfFirst { it.userId == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(muted = muted)
        _contacts.value = list
        persistContacts()
    }

    fun setGroupMuted(id: String, muted: Boolean) {
        val list = _groups.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = list[idx].copy(muted = muted)
        _groups.value = list
        persistGroups()
    }

    fun draftFor(chatId: String): String = drafts[chatId] ?: ""

    fun setDraft(chatId: String, text: String) {
        if (text.isBlank()) drafts.remove(chatId) else drafts[chatId] = text
    }

    fun removeContact(id: String) {
        _contacts.value = _contacts.value.filterNot { it.userId == id }
        _messages.value = _messages.value.toMutableMap().also { it.remove(id) }
        persistContacts(); persistMessages()
    }

    // ---------------- Gruplar ----------------

    fun groupById(id: String): Group? = _groups.value.firstOrNull { it.id == id }

    private fun upsertGroup(g: Group) {
        val list = _groups.value.toMutableList()
        val idx = list.indexOfFirst { it.id == g.id }
        if (idx >= 0) list[idx] = g else list.add(g)
        _groups.value = list
        persistGroups()
    }

    private fun persistGroups() {
        val arr = JSONArray()
        for (g in _groups.value) {
            arr.put(groupToJson(g))
        }
        writeAsync(groupsFile, arr.toString())
    }

    private fun groupToJson(g: Group): JSONObject {
        val members = JSONArray()
        for (m in g.members) {
            members.put(JSONObject().put("u", m.userId).put("k", m.publicKey).put("n", m.name))
        }
        return JSONObject()
            .put("id", g.id)
            .put("name", g.name)
            .put("key", g.keyB64)
            .put("members", members)
            .put("createdAt", g.createdAt)
            .put("avatar", g.avatarB64)
            .put("muted", g.muted)
    }

    private fun groupFromJson(o: JSONObject): Group {
        val members = ArrayList<GroupMember>()
        val arr = o.optJSONArray("members") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            members.add(GroupMember(m.getString("u"), m.optString("k", ""), m.optString("n", "Isimsiz")))
        }
        return Group(
            id = o.getString("id"),
            name = o.optString("name", "Grup"),
            keyB64 = o.optString("key", ""),
            members = members,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            avatarB64 = o.optString("avatar", ""),
            muted = o.optBoolean("muted", false)
        )
    }

    private fun parseGroups(s: String): List<Group> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { groupFromJson(arr.getJSONObject(it)) }
    }

    /** Grup kurar, anahtarı her üyeye 1:1 E2E kanalından gönderir. */
    fun createGroup(name: String, memberIds: List<String>, avatarB64: String = ""): Group {
        val me = _identity.value!!
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val members = ArrayList<GroupMember>()
        members.add(GroupMember(me.userId, me.encPublic, me.displayName))
        for (id in memberIds) {
            val c = contactById(id) ?: continue
            members.add(GroupMember(c.userId, c.publicKey, c.name))
        }
        val group = Group(
            id = "g_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.trim().ifBlank { "Grup" },
            keyB64 = B64.enc(key),
            members = members,
            createdAt = System.currentTimeMillis(),
            avatarB64 = avatarB64
        )
        upsertGroup(group)
        broadcastGroup(group)
        return group
    }

    /** Grubun güncel tanımını (ad, avatar, üyeler) tüm üyelere gönderir. */
    private fun broadcastGroup(group: Group) {
        val me = _identity.value ?: return
        scope.launch {
            val invite = JSONObject()
                .put("type", "group_invite")
                .put("from", me.userId)
                .put("name", me.displayName)
                .put("group", groupToJson(group))
                .toString()
            for (m in group.members) {
                if (m.userId == me.userId) continue
                val c = contactById(m.userId) ?: Contact(m.userId, m.publicKey, m.name, _settings.value!!.relay, System.currentTimeMillis(), false)
                transmitWithRetry(c, invite, Crypto.INFO_MSG)
            }
        }
    }

    /** Gruba yeni üyeler ekler ve güncel tanımı herkese bildirir. */
    fun addGroupMembers(groupId: String, memberIds: List<String>): Group? {
        val group = groupById(groupId) ?: return null
        val members = group.members.toMutableList()
        val existing = members.map { it.userId }.toSet()
        for (id in memberIds) {
            if (existing.contains(id)) continue
            val c = contactById(id) ?: continue
            members.add(GroupMember(c.userId, c.publicKey, c.name))
        }
        val updated = group.copy(members = members)
        upsertGroup(updated)
        broadcastGroup(updated)
        return updated
    }

    /** Grup adını veya fotoğrafını günceller ve herkese bildirir. */
    fun updateGroup(groupId: String, name: String, avatarB64: String): Group? {
        val group = groupById(groupId) ?: return null
        val updated = group.copy(
            name = name.trim().ifBlank { group.name },
            avatarB64 = avatarB64
        )
        upsertGroup(updated)
        broadcastGroup(updated)
        return updated
    }

    fun removeGroupMember(groupId: String, userId: String): Group? {
        val group = groupById(groupId) ?: return null
        if (userId == _identity.value?.userId) return null
        val updated = group.copy(members = group.members.filterNot { it.userId == userId })
        upsertGroup(updated)
        broadcastGroup(updated)
        return updated
    }

    fun removeGroup(id: String) {
        _groups.value = _groups.value.filterNot { it.id == id }
        _messages.value = _messages.value.toMutableMap().also { it.remove(id) }
        persistGroups(); persistMessages()
    }

    fun sendGroupMessage(
        groupId: String,
        content: String,
        type: String = "text",
        viewOnce: Boolean = false,
        fileName: String = "",
        replyToId: String = "",
        replyPreview: String = ""
    ) {
        val group = groupById(groupId) ?: return
        if (content.isBlank()) return
        val me = _identity.value!!
        val msgId = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        appendMessage(
            Message(msgId, groupId, true, content, ts, MessageStatus.PENDING, type, groupId, me.userId, viewOnce, false,
                fileName, replyToId, replyPreview)
        )
        scope.launch {
            val inner = JSONObject()
                .put("type", "group_msg")
                .put("from", me.userId)
                .put("name", me.displayName)
                .put("gid", groupId)
                .put("gname", group.name)
                .put("mtype", type)
                .put("once", viewOnce)
                .put("file", fileName)
                .put("rid", replyToId)
                .put("rprev", replyPreview)
                .put("body", content)
                .put("ts", ts)
                .put("id", msgId)
                .toString()
            var anyOk = false
            for (m in group.members) {
                if (m.userId == me.userId) continue
                val c = contactById(m.userId) ?: Contact(m.userId, m.publicKey, m.name, _settings.value!!.relay, System.currentTimeMillis(), false)
                if (transmitWithRetry(c, inner, Crypto.INFO_MSG)) anyOk = true
            }
            updateStatus(msgId, if (anyOk) MessageStatus.SENT else MessageStatus.FAILED)
            if (viewOnce && anyOk) markViewed(msgId)
        }
    }

    // ---------------- Şifreli yedek ----------------

    private val BACKUP_MAGIC = "NGBK1".toByteArray(Charsets.US_ASCII)

    /** Tüm verileri (kimlik dahil) parola ile şifreleyip yedek üretir. */
    fun exportBackup(password: String): ByteArray {
        // Mesajlar debounce ile yazılır; yedek her zaman bellekteki güncel halini alır.
        persistMessagesNow()
        val root = JSONObject()
        root.put("identity", SecureStore.readSecureString(idFile) ?: "")
        root.put("contacts", SecureStore.readSecureString(contactsFile) ?: "")
        root.put("messages", SecureStore.readSecureString(messagesFile) ?: "")
        root.put("groups", SecureStore.readSecureString(groupsFile) ?: "")
        root.put("settings", SecureStore.readSecureString(settingsFile) ?: "")
        val salt = Crypto.randomBytes(16)
        val nonce = Crypto.randomBytes(12)
        val key = Crypto.deriveKey(password, salt)
        val ct = Crypto.aesGcmEncrypt(key, nonce, root.toString().toByteArray(Charsets.UTF_8))
        return BACKUP_MAGIC + salt + nonce + ct
    }

    /** Yedeği çözer ve mevcut tüm verinin üzerine yazar. */
    fun importBackup(bytes: ByteArray, password: String): Result<Unit> {
        return try {
            require(bytes.size > 33 && bytes.copyOfRange(0, 5).contentEquals(BACKUP_MAGIC)) { "Geçersiz yedek dosyası." }
            val salt = bytes.copyOfRange(5, 21)
            val nonce = bytes.copyOfRange(21, 33)
            val ct = bytes.copyOfRange(33, bytes.size)
            val key = Crypto.deriveKey(password, salt)
            val plain = String(Crypto.aesGcmDecrypt(key, nonce, ct), Charsets.UTF_8)
            val root = JSONObject(plain)
            SecureStore.writeSecureString(idFile, root.optString("identity", ""))
            SecureStore.writeSecureString(contactsFile, root.optString("contacts", "[]"))
            SecureStore.writeSecureString(messagesFile, root.optString("messages", "{}"))
            SecureStore.writeSecureString(groupsFile, root.optString("groups", "[]"))
            SecureStore.writeSecureString(settingsFile, root.optString("settings", ""))
            _identity.value = parseIdentity(root.optString("identity", ""))
            _contacts.value = parseContacts(root.optString("contacts", "[]"))
            _messages.value = parseMessages(root.optString("messages", "{}"))
            _groups.value = parseGroups(root.optString("groups", "[]"))
            _settings.value = parseSettings(root.optString("settings", ""))
            if (_settings.value?.lockEnabled == true) _locked.value = true else _locked.value = false
            persistIdentity(); persistSettings(); persistContacts(); persistMessages(); persistGroups()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Yedek açılamadı (parola yanlış olabilir)."))
        }
    }

    /** Tüm yerel verileri siler ve yeni bir anonim kimlik üretir. */
    fun wipeAll() {
        idFile.delete(); contactsFile.delete(); messagesFile.delete(); settingsFile.delete(); groupsFile.delete()
        _identity.value = newIdentity()
        _settings.value = defaultSettings()
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        _groups.value = emptyList()
        _typing.value = emptySet()
        _locked.value = false
        persistIdentity(); persistSettings(); persistContacts(); persistMessages(); persistGroups()
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

    private fun updateMessage(id: String, transform: (Message) -> Message) =
        updateMessages(setOf(id)) { m -> if (m.id == id) transform(m) else m }

    /** Birden fazla mesajı tek geçişte günceller (toplu) — UI ve disk yazımı bir kez tetiklenir. */
    private fun updateMessages(ids: Set<String>, transform: (Message) -> Message) {
        if (ids.isEmpty()) return
        val map = _messages.value.toMutableMap()
        var changed = false
        for ((key, list) in map) {
            if (list.none { ids.contains(it.id) }) continue
            map[key] = list.map { if (ids.contains(it.id)) transform(it) else it }
            changed = true
        }
        if (!changed) return
        _messages.value = map
        persistMessages()
    }

    private fun updateStatus(id: String, status: MessageStatus) = updateMessage(id) { it.copy(status = status) }

    /** Tek seferlik (view-once) bir mesajı "görüldü" olarak işaretler ve içeriğini siler. */
    fun markViewed(id: String) {
        updateMessage(id) { it.copy(viewed = true, text = "") }
    }

    /** Bir mesajı yalnızca bu cihazdan siler. */
    fun deleteMessageLocally(msgId: String) {
        val map = _messages.value.toMutableMap()
        var changed = false
        for ((chatId, list) in map) {
            if (list.any { it.id == msgId }) {
                map[chatId] = list.filterNot { it.id == msgId }
                changed = true
            }
        }
        if (changed) { _messages.value = map; persistMessages() }
    }

    /** Bir mesajı karşı taraftan da (herkesten) siler. */
    fun deleteMessage(chatId: String, msgId: String, isGroup: Boolean) {
        val me = _identity.value!!.userId
        deleteMessageLocally(msgId)
        scope.launch {
            val inner = JSONObject()
                .put("type", "delete")
                .put("from", me)
                .put("id", msgId)
                .put("gid", if (isGroup) chatId else "")
                .toString()
            if (isGroup) {
                groupById(chatId)?.let { g ->
                    for (m in g.members) {
                        if (m.userId == me) continue
                        val c = contactById(m.userId) ?: continue
                        transmit(c, inner, Crypto.INFO_MSG)
                    }
                }
            } else {
                contactById(chatId)?.let { transmit(it, inner, Crypto.INFO_MSG) }
            }
        }
    }

    /** Mesaj deposunu diske yazar; art arda gelen tetiklemeleri birleştirir (debounce). */
    private fun persistMessages() {
        persistPending = true
        if (persistInFlight) return
        scope.launch {
            persistInFlight = true
            try {
                while (true) {
                    delay(350)
                    persistPending = false
                    SecureStore.writeSecureString(messagesFile, messagesToJson(_messages.value).toString())
                    if (!persistPending) break
                }
            } finally {
                persistInFlight = false
            }
        }
    }

    /** Yedek/alış için anlık (senkron) yazım — beklemedeki debounce'u beklemez. */
    private fun persistMessagesNow() {
        persistPending = false
        SecureStore.writeSecureString(messagesFile, messagesToJson(_messages.value).toString())
    }

    private fun messagesToJson(snapshot: Map<String, List<Message>>): JSONObject {
        val root = JSONObject()
        for ((contactId, list) in snapshot) {
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
                        .put("type", m.type)
                        .put("groupId", m.groupId)
                        .put("senderId", m.senderId)
                        .put("viewOnce", m.viewOnce)
                        .put("viewed", m.viewed)
                        .put("fileName", m.fileName)
                        .put("replyToId", m.replyToId)
                        .put("replyPreview", m.replyPreview)
                        .put("reactions", reactionJson(m.reactions))
                )
            }
            root.put(contactId, arr)
        }
        return root
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
                    MessageStatus.valueOf(o.optString("status", "SENT")),
                    o.optString("type", "text"),
                    o.optString("groupId", ""),
                    o.optString("senderId", ""),
                    o.optBoolean("viewOnce", false),
                    o.optBoolean("viewed", false),
                    o.optString("fileName", ""),
                    o.optString("replyToId", ""),
                    o.optString("replyPreview", ""),
                    parseReactions(o.optJSONObject("reactions"))
                )
            }
        }
        return out
    }

    private fun reactionJson(map: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        return o
    }

    private fun parseReactions(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val m = HashMap<String, String>()
        for (k in o.keys()) m[k] = o.optString(k, "")
        return m
    }

    fun sendMessage(
        contactId: String,
        text: String,
        type: String = "text",
        viewOnce: Boolean = false,
        fileName: String = "",
        replyToId: String = "",
        replyPreview: String = ""
    ) {
        val contact = contactById(contactId) ?: return
        if (text.isBlank()) return
        val msgId = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        appendMessage(
            Message(msgId, contactId, true, text, ts, MessageStatus.PENDING, type, "", _identity.value!!.userId, viewOnce, false,
                fileName, replyToId, replyPreview)
        )
        scope.launch {
            val inner = innerMessage(contact, text, ts, msgId, type, viewOnce, fileName, replyToId, replyPreview)
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
            if (viewOnce && ok) markViewed(msgId)
        }
    }

    private fun innerMessage(
        contact: Contact,
        text: String,
        ts: Long,
        msgId: String,
        type: String,
        viewOnce: Boolean,
        fileName: String = "",
        replyToId: String = "",
        replyPreview: String = ""
    ): String {
        val i = _identity.value!!
        return JSONObject()
            .put("type", "msg")
            .put("from", i.userId)
            .put("name", i.displayName)
            .put("mtype", type)
            .put("once", viewOnce)
            .put("file", fileName)
            .put("rid", replyToId)
            .put("rprev", replyPreview)
            .put("body", text)
            .put("ts", ts)
            .put("id", msgId)
            .toString()
    }

    // ---------------- Yazıyor / okundu / tepki / ilet ----------------

    /** Karşı tarafa "yazıyor" bilgisi gönderir (en fazla 3 saniyede bir). */
    fun sendTyping(chatId: String, isGroup: Boolean) {
        val now = System.currentTimeMillis()
        if (now - (lastTypingSent[chatId] ?: 0L) < 3000) return
        lastTypingSent[chatId] = now
        scope.launch {
            val inner = JSONObject()
                .put("type", "typing")
                .put("from", _identity.value!!.userId)
                .put("gid", if (isGroup) chatId else "")
                .toString()
            if (isGroup) {
                groupById(chatId)?.let { g ->
                    for (m in g.members) {
                        if (m.userId == _identity.value!!.userId) continue
                        val c = contactById(m.userId) ?: continue
                        transmit(c, inner, Crypto.INFO_MSG)
                    }
                }
            } else {
                contactById(chatId)?.let { transmit(it, inner, Crypto.INFO_MSG) }
            }
        }
    }

    fun isTyping(uid: String): Boolean = _typing.value.contains(uid)

    private fun onTyping(uid: String) {
        if (uid.isEmpty() || uid == _identity.value?.userId) return
        _typing.value = _typing.value + uid
        typingJobs[uid]?.cancel()
        typingJobs[uid] = scope.launch {
            delay(5000)
            _typing.value = _typing.value - uid
        }
    }

    /** Açılan sohbetteki okunmamış gelen mesajları okundu olarak işaretler ve bildirir. */
    fun markChatRead(chatId: String, isGroup: Boolean) {
        val list = messagesFor(chatId)
        val unread = list.filter { !it.outgoing && it.status != MessageStatus.READ }
        if (unread.isEmpty()) return
        val ids = unread.mapTo(HashSet()) { it.id }
        updateMessages(ids) { m -> if (m.id in ids) m.copy(status = MessageStatus.READ) else m }
        scope.launch {
            val bySender = unread.filter { it.senderId.isNotEmpty() }.groupBy { it.senderId }
            for ((sender, msgs) in bySender) {
                val ja = JSONArray(); msgs.forEach { ja.put(it.id) }
                val inner = JSONObject()
                    .put("type", "read")
                    .put("from", _identity.value!!.userId)
                    .put("ids", ja)
                    .toString()
                contactById(sender)?.let { transmit(it, inner, Crypto.INFO_MSG) }
            }
        }
    }

    /** Bir mesaja emoji tepkisi verir (aynı emoji tekrar verilirse kaldırır). */
    fun react(chatId: String, msgId: String, emoji: String, isGroup: Boolean) {
        val me = _identity.value!!.userId
        var newEmoji = emoji
        updateMessage(msgId) {
            val cur = it.reactions.toMutableMap()
            if (cur[me] == emoji) { cur.remove(me); newEmoji = "" } else cur[me] = emoji
            it.copy(reactions = cur)
        }
        scope.launch {
            val inner = JSONObject()
                .put("type", "reaction")
                .put("from", me)
                .put("id", msgId)
                .put("emoji", newEmoji)
                .put("gid", if (isGroup) chatId else "")
                .toString()
            if (isGroup) {
                groupById(chatId)?.let { g ->
                    for (m in g.members) {
                        if (m.userId == me) continue
                        val c = contactById(m.userId) ?: continue
                        transmit(c, inner, Crypto.INFO_MSG)
                    }
                }
            } else {
                contactById(chatId)?.let { transmit(it, inner, Crypto.INFO_MSG) }
            }
        }
    }

    /** Bir mesajı başka bir sohbete iletir. */
    fun forwardMessage(srcChatId: String, msgId: String, targetId: String, targetIsGroup: Boolean) {
        val msg = messagesFor(srcChatId).firstOrNull { it.id == msgId } ?: return
        val kind = if (msg.type == "image" || msg.type == "audio" || msg.type == "file") msg.type else "text"
        val content = msg.text
        val name = msg.fileName
        if (targetIsGroup) sendGroupMessage(targetId, content, kind, false, name)
        else sendMessage(targetId, content, kind, false, name)
    }

    /** Bir iç mesajı E2E şifreleyip gönderir; MQTT'de kopmuşsa yeniden bağlanıp dener. */
    private suspend fun transmitWithRetry(contact: Contact, inner: String, info: ByteArray): Boolean {
        var ok = transmit(contact, inner, info)
        if (!ok && _settings.value?.transport == "mqtt") {
            for (i in 0 until 4) {
                connectMqtt()
                delay(2000)
                ok = transmit(contact, inner, info)
                if (ok) break
            }
        }
        return ok
    }

    private fun sendHello(contact: Contact) {
        scope.launch {
            val i = _identity.value!!
            val inner = JSONObject()
                .put("type", "hello")
                .put("from", i.userId)
                .put("name", i.displayName)
                .put("avatar", i.avatarB64)
                .put("bio", i.bio)
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
            relay.send(contact.relay, body, proxyOrNull())
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
        val torKey = if (s.useTor) "tor:${s.torHost}:${s.torPort}" else "clear"
        val key = "${s.mqttHost}:${s.mqttPort}:${s.mqttTls}:$torKey:${me.userId}"
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
            onMessage = { raw -> if (processIncoming(raw)) notifyIncoming() },
            onConnected = { flushOutbox() },
            torHost = if (s.useTor) s.torHost.ifBlank { "127.0.0.1" } else null,
            torPort = s.torPort
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

    @Volatile
    private var lastIncomingChatId: String = ""

    /** Sessiz olmayan sohbette yeni mesaj varsa bildirim gönderir. */
    private fun notifyIncoming() {
        try {
            val chatId = lastIncomingChatId
            if (chatId.isEmpty()) return
            val muted = contactById(chatId)?.muted == true || groupById(chatId)?.muted == true
            if (!muted) onNewMessage?.invoke(1)
        } catch (_: Exception) {
        }
    }

    /**
     * Periyodik bakım: bağlantıyı canlı tutar, bekleyen outbox'ı boşaltır ve
     * durumu PENDING/FAILED kalmış giden mesajları yeniden gönderir.
     * (Alıcı mesaj kimliğine göre yineler; çift gönderim zararsızdır.)
     */
    fun maintenance() {
        if (_settings.value?.transport == "mqtt") {
            connectMqtt()
            flushOutbox()
        }
        val now = System.currentTimeMillis()
        if (now - lastRetryAt > 60_000) {
            lastRetryAt = now
            retryUnsent()
        }
    }

    @Volatile private var lastRetryAt = 0L

    private fun retryUnsent() {
        val me = _identity.value ?: return
        for ((chatId, list) in _messages.value) {
            val isGroup = groupById(chatId) != null
            val contact = if (isGroup) null else contactById(chatId) ?: continue
            for (m in list) {
                if (!m.outgoing || m.text.isBlank()) continue
                if (m.status != MessageStatus.PENDING && m.status != MessageStatus.FAILED) continue
                if (System.currentTimeMillis() - m.ts < 20_000) continue
                scope.launch {
                    if (isGroup) {
                        val g = groupById(chatId) ?: return@launch
                        val inner = JSONObject()
                            .put("type", "group_msg")
                            .put("from", me.userId)
                            .put("name", me.displayName)
                            .put("gid", chatId)
                            .put("gname", g.name)
                            .put("mtype", m.type)
                            .put("once", m.viewOnce)
                            .put("file", m.fileName)
                            .put("rid", m.replyToId)
                            .put("rprev", m.replyPreview)
                            .put("body", m.text)
                            .put("ts", m.ts)
                            .put("id", m.id)
                            .toString()
                        var anyOk = false
                        for (mem in g.members) {
                            if (mem.userId == me.userId) continue
                            val c = contactById(mem.userId) ?: continue
                            if (transmit(c, inner, Crypto.INFO_MSG)) anyOk = true
                        }
                        if (anyOk) updateStatus(m.id, MessageStatus.SENT)
                    } else {
                        val inner = innerMessage(contact!!, m.text, m.ts, m.id, m.type, m.viewOnce, m.fileName, m.replyToId, m.replyPreview)
                        if (transmit(contact, inner, Crypto.INFO_MSG)) updateStatus(m.id, MessageStatus.SENT)
                    }
                }
            }
        }
    }

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
        val envelopes = relay.pull(s.relay, me.userId, proxyOrNull())
        var newCount = 0
        for (raw in envelopes) {
            if (processIncoming(raw)) newCount++
        }
        if (newCount > 0) notifyIncoming()
        return newCount
    }

    private fun handlePlain(env: Envelope, plainJson: String): Boolean {
        val o = JSONObject(plainJson)
        val from = o.optString("from", "")
        val nameOpt = if (o.has("name")) o.optString("name", "") else null
        val senderPubB64 = B64.enc(env.senderPub)
        if (from.isEmpty()) return false

        val type = o.optString("type", "msg")
        val existing = contactById(from)

        // Geçici bilgi paketleri (yazıyor/okundu/tepki) yeni kişi oluşturmaz.
        if (existing == null && (type == "typing" || type == "read" || type == "reaction" || type == "delete")) return false

        val avatar = if (o.has("avatar")) o.optString("avatar", "") else null
        val bio = if (o.has("bio")) o.optString("bio", "") else null

        if (existing == null) {
            upsertContact(
                Contact(
                    userId = from,
                    publicKey = senderPubB64,
                    name = (nameOpt ?: "").ifBlank { "Isimsiz" },
                    relay = _settings.value!!.relay,
                    addedAt = System.currentTimeMillis(),
                    pending = false,
                    avatarB64 = avatar ?: "",
                    bio = bio ?: ""
                )
            )
        } else {
            updateContactProfile(from, nameOpt ?: "", avatar, bio)
            // Zarfın açık anahtarı kayıtlıkinden farklıysa güncelle: eski anahtara
            // şifrelenen giden mesajlar karşı tarafta sessizce düşer.
            val cur = contactById(from)
            if (cur != null && cur.publicKey != senderPubB64) {
                upsertContact(cur.copy(publicKey = senderPubB64))
            }
        }

        when (type) {
            "profile" -> {
                return false
            }
            "group_invite" -> {
                val g = o.optJSONObject("group") ?: return false
                val incoming = groupFromJson(g)
                val prev = groupById(incoming.id)
                upsertGroup(incoming)
                return prev == null
            }
            "group_msg" -> {
                val gid = o.optString("gid", "")
                if (gid.isEmpty()) return false
                if (groupById(gid) == null) {
                    val me = _identity.value!!
                    upsertGroup(
                        Group(
                            id = gid,
                            name = o.optString("gname", "Grup"),
                            keyB64 = "",
                            members = listOf(
                                GroupMember(me.userId, me.encPublic, me.displayName),
                                GroupMember(from, senderPubB64, (nameOpt ?: "").ifBlank { "Isimsiz" })
                            ),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                val msgId = o.optString("id", UUID.randomUUID().toString())
                if (messagesFor(gid).any { it.id == msgId }) return false
                appendMessage(
                    Message(
                        msgId, gid, false, o.optString("body", ""),
                        o.optLong("ts", System.currentTimeMillis()), MessageStatus.DELIVERED,
                        o.optString("mtype", "text"), gid, from,
                        o.optBoolean("once", false), false,
                        o.optString("file", ""), o.optString("rid", ""), o.optString("rprev", "")
                    )
                )
                lastIncomingChatId = gid
                return true
            }
            "msg" -> {
                val msgId = o.optString("id", UUID.randomUUID().toString())
                if (messagesFor(from).any { it.id == msgId }) return false
                appendMessage(
                    Message(
                        msgId, from, false, o.optString("body", ""),
                        o.optLong("ts", System.currentTimeMillis()), MessageStatus.DELIVERED,
                        o.optString("mtype", "text"), "", from,
                        o.optBoolean("once", false), false,
                        o.optString("file", ""), o.optString("rid", ""), o.optString("rprev", "")
                    )
                )
                lastIncomingChatId = from
                return true
            }
            "typing" -> {
                onTyping(from)
                return false
            }
            "read" -> {
                val ids = o.optJSONArray("ids")
                if (ids != null && ids.length() > 0) {
                    val idSet = HashSet<String>()
                    for (i in 0 until ids.length()) idSet.add(ids.getString(i))
                    updateMessages(idSet) { m -> if (m.id in idSet) m.copy(status = MessageStatus.READ) else m }
                }
                return false
            }
            "reaction" -> {
                val mid = o.optString("id", "")
                val emoji = o.optString("emoji", "")
                if (mid.isNotEmpty()) updateMessage(mid) {
                    val cur = it.reactions.toMutableMap()
                    if (emoji.isBlank()) cur.remove(from) else cur[from] = emoji
                    it.copy(reactions = cur)
                }
                return false
            }
            "delete" -> {
                val mid = o.optString("id", "")
                if (mid.isNotEmpty()) deleteMessageLocally(mid)
                return false
            }
            "hello" -> {
                // Karşı taraf bizi yeni ekledi: biz de profilimizi geri gönderelim.
                if (existing == null) {
                    contactById(from)?.let { sendHello(it) }
                }
                return existing == null
            }
        }
        // bilinmeyen tür
        return existing == null
    }
}
