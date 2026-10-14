package com.gizli.messenger.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gizli.messenger.BuildConfig
import com.gizli.messenger.data.Contact
import com.gizli.messenger.data.Group
import com.gizli.messenger.data.Message
import com.gizli.messenger.data.MessageStatus
import com.gizli.messenger.data.Repo
import com.gizli.messenger.service.PollService
import com.gizli.messenger.update.UpdateInfo
import com.gizli.messenger.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Screen {
    data object Home : Screen
    data class Chat(val contactId: String) : Screen
    data class GroupInfo(val groupId: String) : Screen
    data object Add : Screen
    data object Settings : Screen
    data object Update : Screen
}

@Composable
fun GizliApp() {
    val context = LocalContext.current
    val identity by Repo.identity.collectAsState()
    val contacts by Repo.contacts.collectAsState()
    val groups by Repo.groups.collectAsState()
    val settings by Repo.settings.collectAsState()
    val allMessages by Repo.messages.collectAsState()
    val locked by Repo.locked.collectAsState()

    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var autoProgress by remember { mutableStateOf<Int?>(null) }
    var autoChecked by remember { mutableStateOf(false) }
    var updatePrompt by remember { mutableStateOf<UpdateInfo?>(null) }

    fun startUpdateDownload(info: UpdateInfo) {
        val um = UpdateManager(context.applicationContext)
        if (!um.canInstallPackages()) {
            Toast.makeText(context, "Kurulum izni gerekli. Ayarlardan bu uygulamaya izin ver.", Toast.LENGTH_LONG).show()
            um.openInstallPermissionSettings()
            return
        }
        updatePrompt = null
        scope.launch {
            autoProgress = 0
            val res = um.downloadApk(info) { p -> autoProgress = p }
            autoProgress = null
            res.fold(
                onSuccess = { file ->
                    Toast.makeText(context, "Güncelleme kuruluyor…", Toast.LENGTH_SHORT).show()
                    um.install(file)
                },
                onFailure = { Toast.makeText(context, "Güncelleme hatası: ${it.message}", Toast.LENGTH_LONG).show() }
            )
        }
    }

    // Ekran görüntüsü engeli (FLAG_SECURE) + sistem çubukları temaya göre
    val secure = settings?.secureScreen ?: true
    val themeId = settings?.theme ?: "default"
    val activity = context as? android.app.Activity
    LaunchedEffect(secure, themeId) {
        val w = activity?.window ?: return@LaunchedEffect
        if (secure) w.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        ) else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val barColor = when (themeId) {
            "amoled" -> android.graphics.Color.BLACK
            "ocean" -> android.graphics.Color.parseColor("#071A24")
            "sunset" -> android.graphics.Color.parseColor("#1A0F14")
            "forest" -> android.graphics.Color.parseColor("#0B140C")
            "violet" -> android.graphics.Color.parseColor("#120A1A")
            else -> android.graphics.Color.parseColor("#0B0F14")
        }
        w.statusBarColor = barColor
        w.navigationBarColor = barColor
    }

    // Ön plan bağlantısı: MQTT ise sürekli açık tut, relay ise sık yokla
    LaunchedEffect(identity, settings) {
        if (!identity?.displayName.isNullOrBlank()) {
            if (settings?.transport == "mqtt") {
                Repo.connectMqtt()
                while (true) {
                    delay(30000)
                    Repo.connectMqtt()
                }
            } else {
                while (true) {
                    runCatching { Repo.pollOnce() }
                    delay(3000)
                }
            }
        }
    }

    // Uygulamaya ilk girişte güncellemeyi kontrol et; varsa indirme bildirimi (diyalog) göster.
    LaunchedEffect(identity, locked, settings?.autoUpdate) {
        if (autoChecked) return@LaunchedEffect
        val me = identity ?: return@LaunchedEffect
        if (locked) return@LaunchedEffect
        if (me.displayName.isBlank()) return@LaunchedEffect
        if (settings?.autoUpdate != true) { autoChecked = true; return@LaunchedEffect }
        autoChecked = true
        val um = UpdateManager(context.applicationContext)
        val info = um.checkForUpdate().getOrNull() ?: return@LaunchedEffect
        updatePrompt = info
    }

    GizliTheme(themeId = settings?.theme ?: "default") {
        val id = identity
        updatePrompt?.let { info ->
            AlertDialog(
                onDismissRequest = { updatePrompt = null },
                icon = { Icon(Icons.Filled.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Yeni sürüm v${info.versionName}") },
                text = {
                    Column {
                        Text("Yeni bir sürüm hazır. Şimdi indirip kurmak ister misin?")
                        if (info.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(info.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { startUpdateDownload(info) }) { Text("İndir") }
                },
                dismissButton = {
                    TextButton(onClick = { updatePrompt = null }) { Text("Daha sonra") }
                }
            )
        }
        autoProgress?.let { p ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Güncelleniyor") },
                text = {
                    Column {
                        Text("Yeni sürüm indiriliyor, lütfen bekleyin…")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { p / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("%$p", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
        when {
            id == null -> CenterLoading()
            locked -> LockScreen()
            id.displayName.isBlank() -> Onboarding { name ->
                Repo.setDisplayName(name)
                if (Repo.settings.value?.backgroundPolling == true) PollService.start(context)
            }

            else -> {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (val s = screen) {
                    is Screen.Home -> HomeScreen(
                        contacts = contacts,
                        groups = groups,
                        allMessages = allMessages,
                        onOpen = { screen = Screen.Chat(it) },
                        onAdd = { screen = Screen.Add },
                        onSettings = { screen = Screen.Settings },
                        onUpdate = { screen = Screen.Update },
                        onNewGroup = { showCreateGroup = true }
                    )

                    is Screen.Chat -> {
                        val chatId = s.contactId
                        val group = Repo.groupById(chatId)
                        val contact = Repo.contactById(chatId)
                        val fwdTargets = remember(contacts, groups) {
                            buildList {
                                contacts.forEach { add(Triple(it.userId, it.name, false)) }
                                groups.forEach { add(Triple(it.id, it.name, true)) }
                            }
                        }
                        ChatScreen(
                            title = group?.name ?: contact?.name ?: "Sohbet",
                            avatarB64 = group?.avatarB64 ?: contact?.avatarB64 ?: "",
                            isGroup = group != null,
                            chatId = chatId,
                            messages = allMessages[chatId].orEmpty(),
                            nameOf = { uid ->
                                if (uid == Repo.identity.value?.userId) "Sen"
                                else Repo.contactById(uid)?.name ?: uid.take(8)
                            },
                            onBack = { screen = Screen.Home },
                            onGroupInfo = if (group != null) ({ screen = Screen.GroupInfo(chatId) }) else null,
                            onSend = { t, rid, rprev ->
                                if (group != null) Repo.sendGroupMessage(chatId, t, replyToId = rid, replyPreview = rprev)
                                else Repo.sendMessage(chatId, t, replyToId = rid, replyPreview = rprev)
                            },
                            onSendMedia = { content, mtype, once, fname, rid, rprev ->
                                if (group != null) Repo.sendGroupMessage(chatId, content, mtype, once, fname, rid, rprev)
                                else Repo.sendMessage(chatId, content, mtype, once, fname, rid, rprev)
                            },
                            onViewOnce = { mid -> Repo.markViewed(mid) },
                            onReact = { mid, emoji -> Repo.react(chatId, mid, emoji, group != null) },
                            forwardTargets = fwdTargets,
                            onForward = { mid, targetId, targetIsGroup ->
                                Repo.forwardMessage(chatId, mid, targetId, targetIsGroup)
                            }
                        )
                    }

                    is Screen.GroupInfo -> {
                        val g = Repo.groupById(s.groupId)
                        if (g == null) {
                            screen = Screen.Home
                        } else {
                            GroupInfoScreen(
                                group = g,
                                contacts = contacts,
                                myId = Repo.identity.value?.userId ?: "",
                                onBack = { screen = Screen.Chat(g.id) },
                                onUpdate = { name, avatar -> Repo.updateGroup(g.id, name, avatar) },
                                onAddMembers = { ids -> Repo.addGroupMembers(g.id, ids) },
                                onRemoveMember = { uid -> Repo.removeGroupMember(g.id, uid) },
                                onDelete = { Repo.removeGroup(g.id); screen = Screen.Home }
                            )
                        }
                    }

                    Screen.Add -> AddContactScreen(onBack = { screen = Screen.Home })

                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Home },
                        onWiped = { screen = Screen.Home }
                    )

                    Screen.Update -> UpdateScreen(onBack = { screen = Screen.Home })
                }

                if (showCreateGroup) {
                    CreateGroupDialog(
                        contacts = contacts,
                        onDismiss = { showCreateGroup = false },
                        onCreate = { gname, ids, gavatar ->
                            Repo.createGroup(gname, ids, gavatar)
                            showCreateGroup = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Onboarding(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("nullnet", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Telefon numarası yok, e-posta yok. Kimliğin cihazında üretilen rastgele bir anahtar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Görünecek ad") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (name.isNotBlank()) onDone(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Başla") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    contacts: List<Contact>,
    groups: List<Group>,
    allMessages: Map<String, List<Message>>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onUpdate: () -> Unit,
    onNewGroup: () -> Unit
) {
    val typingSet by Repo.typing.collectAsState()
    val meId = Repo.identity.value?.userId ?: ""

    val sortedGroups = remember(groups, allMessages) {
        groups.sortedByDescending { g -> allMessages[g.id]?.lastOrNull()?.ts ?: g.createdAt }
    }
    val sortedContacts = remember(contacts, allMessages) {
        contacts.sortedByDescending { c -> allMessages[c.userId]?.lastOrNull()?.ts ?: c.addedAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(com.gizli.messenger.R.drawable.ic_logo),
                            contentDescription = "nullnet",
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("nullnet", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = onNewGroup) { Icon(Icons.Filled.Group, "Yeni grup") }
                    IconButton(onClick = onUpdate) { Icon(Icons.Filled.SystemUpdate, "Güncelleme") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Ayarlar") }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Kişi ekle")
            }
        }
    ) { pad ->
        if (contacts.isEmpty() && groups.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Henüz kişi yok", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kişi ekle → kendi davet kodunu paylaş, ya da arkadaşının kodunu yapıştır.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                if (sortedGroups.isNotEmpty()) {
                    item { SectionHeader("Gruplar") }
                    items(sortedGroups, key = { it.id }) { g ->
                        val msgs = allMessages[g.id].orEmpty()
                        val last = msgs.lastOrNull()
                        val unread = msgs.count { !it.outgoing && it.status != MessageStatus.READ }
                        ConversationRow(
                            title = g.name,
                            subtitle = preview(last),
                            avatarText = g.name.take(1).uppercase(),
                            avatarB64 = g.avatarB64,
                            isGroup = true,
                            time = last?.let { timeText(it.ts) },
                            unread = unread,
                            muted = g.muted,
                            typing = typingSet.any { it != meId && g.members.any { m -> m.userId == it } },
                            onClick = { onOpen(g.id) },
                            onLongClick = { Repo.setGroupMuted(g.id, !g.muted) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (sortedContacts.isNotEmpty()) {
                    item { SectionHeader("Kişiler") }
                    items(sortedContacts, key = { it.userId }) { c ->
                        val msgs = allMessages[c.userId].orEmpty()
                        val last = msgs.lastOrNull()
                        val unread = msgs.count { !it.outgoing && it.status != MessageStatus.READ }
                        ConversationRow(
                            title = c.name,
                            subtitle = preview(last) ?: (c.userId.take(12) + "…"),
                            avatarText = c.name.take(1).uppercase(),
                            avatarB64 = c.avatarB64,
                            isGroup = false,
                            time = last?.let { timeText(it.ts) },
                            unread = unread,
                            muted = c.muted,
                            typing = typingSet.contains(c.userId),
                            onClick = { onOpen(c.userId) },
                            onLongClick = { Repo.setContactMuted(c.userId, !c.muted) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun preview(last: Message?): String? {
    if (last == null) return null
    val body = if (last.type == "image") "📷 Fotoğraf" else last.text
    return (if (last.outgoing) "Sen: " else "") + body
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String,
    subtitle: String?,
    avatarText: String,
    avatarB64: String,
    isGroup: Boolean,
    time: String? = null,
    unread: Int = 0,
    muted: Boolean = false,
    typing: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick ?: {}).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarB64(b64 = avatarB64, fallback = avatarText, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGroup) {
                    Icon(Icons.Filled.Group, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.NotificationsOff, null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (typing) {
                Text("yazıyor…", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            } else if (subtitle != null) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (time != null) {
                Text(time, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unread > 0) {
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(
                            if (unread > 99) "99+" else unread.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/** Base64 avatarı (varsa) yuvarlak gösterir, yoksa baş harfi. */
@Composable
fun AvatarB64(b64: String, fallback: String, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(b64) { decodeB64Bitmap(b64) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
        modifier = Modifier.size(size)
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    fallback.ifBlank { "?" },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private val bitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
}

private fun decodeB64Bitmap(b64: String): android.graphics.Bitmap? {
    if (b64.isBlank()) return null
    bitmapCache.get(b64)?.let { return it }
    return runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmapCache.put(b64, bmp)
        bmp
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    title: String,
    avatarB64: String,
    isGroup: Boolean,
    chatId: String,
    messages: List<Message>,
    nameOf: (String) -> String,
    onBack: () -> Unit,
    onGroupInfo: (() -> Unit)?,
    onSend: (String, String, String) -> Unit,
    onSendMedia: (String, String, Boolean, String, String, String) -> Unit,
    onViewOnce: (String) -> Unit,
    onReact: (String, String) -> Unit,
    forwardTargets: List<Triple<String, String, Boolean>>,
    onForward: (String, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var text by remember(chatId) { mutableStateOf(Repo.draftFor(chatId)) }
    var attachMenu by remember { mutableStateOf(false) }
    var pendingOnce by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    var pendingDownloadName by remember { mutableStateOf("dosya") }
    var pendingDownloadMime by remember { mutableStateOf("application/octet-stream") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var menuMsg by remember { mutableStateOf<Message?>(null) }
    var forwardMsg by remember { mutableStateOf<Message?>(null) }
    var deletingMsg by remember { mutableStateOf<Message?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordSecs by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var highlightId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }

    DisposableEffect(chatId) {
        onDispose { Repo.setDraft(chatId, text) }
    }

    fun scrollToMessage(id: String) {
        val list = if (searchQuery.isBlank()) messages
        else messages.filter { it.text.contains(searchQuery, true) || it.fileName.contains(searchQuery, true) }
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            highlightId = id
            scope.launch {
                listState.animateScrollToItem(idx)
                delay(1500)
                if (highlightId == id) highlightId = null
            }
        }
    }

    val typingSet by Repo.typing.collectAsState()
    val groupMemberIds = remember(chatId, isGroup) {
        if (isGroup) Repo.groupById(chatId)?.members?.map { it.userId }?.toSet() ?: emptySet() else emptySet()
    }
    val typingNames = typingSet
        .filter { it != Repo.identity.value?.userId && (if (isGroup) groupMemberIds.contains(it) else it == chatId) }
        .map { nameOf(it) }
    val typingText = when {
        typingNames.size == 1 -> "${typingNames[0]} yazıyor…"
        typingNames.size > 1 -> "birkaç kişi yazıyor…"
        else -> null
    }

    LaunchedEffect(chatId) { Repo.markChatRead(chatId, isGroup) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) Repo.markChatRead(chatId, isGroup) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = compressImageToBase64(context, uri)
            if (b64 != null) {
                val r = replyTo
                onSendMedia(b64, "image", pendingOnce, "", r?.id ?: "", r?.let { previewOf(it) } ?: "")
            }
            pendingOnce = false; replyTo = null
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val res = readUriBytes(context, uri, 1_000_000)
            if (res == null) Toast.makeText(context, "Dosya çok büyük (üst sınır ~1 MB).", Toast.LENGTH_LONG).show()
            else {
                val b64 = android.util.Base64.encodeToString(res.first, android.util.Base64.NO_WRAP)
                val r = replyTo
                onSendMedia(b64, "file", false, res.second, r?.id ?: "", r?.let { previewOf(it) } ?: "")
                replyTo = null
            }
        }
    }
    val downloader = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(pendingDownloadMime)) { uri ->
        val data = pendingDownload
        if (uri != null && data != null) saveBase64ToUri(context, uri, data)
        pendingDownload = null
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(context, "Ses kaydı için tekrar dokun.", Toast.LENGTH_SHORT).show()
        else Toast.makeText(context, "Mikrofon izni gerekli.", Toast.LENGTH_LONG).show()
    }

    fun startRecording() {
        try {
            val f = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(24000)
            rec.setOutputFile(f.absolutePath)
            rec.prepare(); rec.start()
            recorder = rec; recordFile = f; recording = true; recordSecs = 0
        } catch (e: Exception) {
            Toast.makeText(context, "Kayıt başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecording(send: Boolean) {
        val rec = recorder ?: return
        val f = recordFile
        recording = false
        try { rec.stop() } catch (_: Exception) { }
        try { rec.release() } catch (_: Exception) { }
        recorder = null
        if (!send) { f?.delete(); return }
        if (f != null && f.exists()) {
            val bytes = f.readBytes()
            if (bytes.size > 800_000) Toast.makeText(context, "Kayıt çok uzun.", Toast.LENGTH_LONG).show()
            else {
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val r = replyTo
                onSendMedia(b64, "audio", false, "", r?.id ?: "", r?.let { previewOf(it) } ?: "")
                replyTo = null
            }
            f.delete()
        }
    }

    LaunchedEffect(recording) {
        if (recording) {
            while (recording) { delay(1000); recordSecs += 1; if (recordSecs >= 60) stopRecording(true) }
        }
    }

    // Sohbete girildiğinde her zaman en alta konumlan.
    LaunchedEffect(chatId) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }
    // Yeni mesaj geldiğinde, kullanıcı zaten alttaysa otomatik kaydır.
    LaunchedEffect(messages.size) {
        if (messages.isEmpty() || searchQuery.isNotBlank()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val total = info.totalItemsCount
        if (total == 0 || lastVisible >= total - 2 || lastVisible == -1) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarB64(b64 = avatarB64, fallback = title.take(1).uppercase(), size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title)
                            if (typingText != null) Text(typingText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) {
                        Icon(Icons.Filled.Search, "Ara")
                    }
                    if (onGroupInfo != null) {
                        IconButton(onClick = onGroupInfo) { Icon(Icons.Filled.MoreVert, "Grup bilgisi") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Mesajlarda ara…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, "Temizle") }
                    }
                )
            }
            val displayMessages = if (searchQuery.isBlank()) messages
            else messages.filter {
                it.text.contains(searchQuery, ignoreCase = true) || it.fileName.contains(searchQuery, ignoreCase = true)
            }
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("Uçtan uca şifreli sohbet", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "İlk mesajı sen yaz. Mesajları sağa kaydırarak yanıtlayabilirsin.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(displayMessages, key = { _, m -> m.id }) { index, m ->
                        val prev = displayMessages.getOrNull(index - 1)
                        if (prev == null || !sameDay(prev.ts, m.ts)) DateSeparator(m.ts)
                        MessageBubble(
                            m = m,
                            senderName = if (isGroup && !m.outgoing) nameOf(m.senderId) else null,
                            nameById = nameOf,
                            highlighted = m.id == highlightId,
                            onOpenImage = { viewing = m },
                            onLongPress = { menuMsg = m },
                            onReplyClick = { scrollToMessage(it) },
                            onSwipeReply = { replyTo = m },
                            onDoubleTap = { onReact(m.id, "👍") }
                        )
                    }
                }
            }

            if (replyTo != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Yanıtlanıyor", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(previewOf(replyTo!!), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, "Vazgeç") }
                }
            }

            if (recording) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("● Kayıt", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(formatDuration(recordSecs))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { stopRecording(false) }) { Text("Vazgeç") }
                    TextButton(onClick = { stopRecording(true) }) { Text("Gönder") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        IconButton(onClick = { attachMenu = true }) {
                            Icon(Icons.Filled.AttachFile, "Ekle", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Fotoğraf gönder") },
                                leadingIcon = { Icon(Icons.Filled.Image, null) },
                                onClick = { attachMenu = false; pendingOnce = false; picker.launch("image/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("Tek seferlik fotoğraf") },
                                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                                onClick = { attachMenu = false; pendingOnce = true; picker.launch("image/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("Dosya paylaş") },
                                leadingIcon = { Icon(Icons.Filled.AttachFile, null) },
                                onClick = { attachMenu = false; filePicker.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; if (it.isNotBlank()) Repo.sendTyping(chatId, isGroup) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Mesaj yaz…") },
                        maxLines = 5
                    )
                    Spacer(Modifier.width(8.dp))
                    if (text.isNotBlank()) {
                        IconButton(onClick = {
                            val r = replyTo
                            onSend(text.trim(), r?.id ?: "", r?.let { previewOf(it) } ?: "")
                            text = ""; replyTo = null; Repo.setDraft(chatId, "")
                        }) { Icon(Icons.Filled.Send, "Gönder", tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        IconButton(
                            onClick = {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) startRecording()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        ) { Icon(Icons.Filled.Mic, "Sesli mesaj", tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }

    viewing?.let { msg ->
        ImageViewer(
            message = msg,
            onClose = {
                if (msg.viewOnce && !msg.outgoing) onViewOnce(msg.id)
                viewing = null
            },
            onDownload = if (!msg.viewOnce && msg.text.isNotBlank()) ({
                pendingDownload = msg.text; pendingDownloadMime = "image/jpeg"
                downloader.launch("nullnet_" + msg.id.take(8) + ".jpg")
            }) else null
        )
    }

    menuMsg?.let { m ->
        MessageActionsDialog(
            onReply = { replyTo = m; menuMsg = null },
            onReact = { e -> onReact(m.id, e); menuMsg = null },
            onCopy = {
                val clip = previewOf(m).ifBlank { m.text }
                if (clip.isNotBlank()) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("mesaj", clip))
                }
                menuMsg = null
            },
            onForward = { forwardMsg = m; menuMsg = null },
            onDelete = { deletingMsg = m; menuMsg = null },
            onDismiss = { menuMsg = null }
        )
    }

    forwardMsg?.let { m ->
        ForwardDialog(
            targets = forwardTargets,
            onPick = { id, isGrp -> onForward(m.id, id, isGrp); forwardMsg = null },
            onDismiss = { forwardMsg = null }
        )
    }

    deletingMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { deletingMsg = null },
            title = { Text("Mesajı sil") },
            text = { Text("Bu mesajı silmek istediğine emin misin?") },
            confirmButton = {
                TextButton(onClick = { Repo.deleteMessageLocally(m.id); deletingMsg = null }) {
                    Text("Benden sil")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deletingMsg = null }) { Text("Vazgeç") }
                    if (m.outgoing) {
                        TextButton(onClick = {
                            Repo.deleteMessage(chatId, m.id, isGroup); deletingMsg = null
                        }) { Text("Herkesten sil", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        )
    }
}

@Composable
private fun MessageActionsDialog(
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    emojis.forEach { e ->
                        Text(e, fontSize = 26.sp, modifier = Modifier.clickable { onReact(e) }.padding(4.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TextButton(onClick = onReply, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Yanıtla")
                }
                TextButton(onClick = onForward, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("İlet")
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Kopyala")
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ForwardDialog(
    targets: List<Triple<String, String, Boolean>>,
    onPick: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("İlet", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                if (targets.isEmpty()) {
                    Text("İletilecek sohbet yok.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        targets.forEach { (id, name, isGrp) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(id, isGrp) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isGrp) { Icon(Icons.Filled.Group, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                                Text(name)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(ts: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                dayText(ts),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: Message,
    senderName: String?,
    nameById: (String) -> String,
    highlighted: Boolean,
    onOpenImage: () -> Unit,
    onLongPress: () -> Unit,
    onReplyClick: (String) -> Unit,
    onSwipeReply: () -> Unit,
    onDoubleTap: (() -> Unit)? = null
) {
    val isOut = m.outgoing
    val baseColor = if (isOut) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else MaterialTheme.colorScheme.surfaceVariant
    val bubbleColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else baseColor
    val align = if (isOut) Alignment.CenterEnd else Alignment.CenterStart
    var dragX by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragX = (dragX + delta).coerceIn(0f, 220f) }
    Box(
        Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Horizontal,
                state = dragState,
                onDragStopped = {
                    if (dragX > 120f) onSwipeReply()
                    dragX = 0f
                }
            ),
        contentAlignment = align
    ) {
        if (dragX > 0f) {
            Icon(
                Icons.Filled.Reply, null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = (dragX / 220f).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp)
            )
        }
        Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
            modifier = Modifier.offset { IntOffset(dragX.roundToInt(), 0) }
        ) {
            if (senderName != null) {
                Text(
                    senderName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                    onDoubleClick = { onDoubleTap?.invoke() ?: onLongPress() }
                )
            ) {
                Column {
                    if (m.replyPreview.isNotBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = m.replyToId.isNotBlank()) { onReplyClick(m.replyToId) }
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Reply, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(m.replyPreview, fontSize = 12.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when {
                        m.type == "image" && m.viewOnce -> {
                            val label = when {
                                m.text.isNotBlank() && !isOut -> "Tek seferlik fotoğraf · açmak için dokun"
                                isOut && m.text.isNotBlank() -> "Tek seferlik fotoğraf · gönderiliyor"
                                isOut -> "Tek seferlik fotoğraf gönderildi"
                                else -> "Tek seferlik fotoğraf görüntülendi"
                            }
                            Row(
                                Modifier.clickable(enabled = m.text.isNotBlank()) { onOpenImage() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        m.type == "image" -> ImageBubble(m.text, onClick = onOpenImage)
                        m.type == "audio" -> AudioBubble(b64 = m.text, outgoing = isOut)
                        m.type == "file" -> FileBubble(name = m.fileName.ifBlank { "dosya" }, b64 = m.text, sizeHint = m.text.length)
                        else -> Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    if (m.reactions.isNotEmpty()) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                            val grouped = m.reactions.values.groupingBy { it }.eachCount()
                            grouped.forEach { (emoji, count) ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        emoji + if (count > 1) " $count" else "",
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                timeText(m.ts) + statusSuffix(m),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun AudioBubble(b64: String, outgoing: Boolean) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            if (playing) {
                player?.release(); player = null; playing = false
            } else {
                val tmp = File(context.cacheDir, "play_${System.currentTimeMillis()}.m4a")
                tmp.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                val mp = MediaPlayer()
                mp.setDataSource(tmp.absolutePath)
                mp.setOnCompletionListener { playing = false; it.release(); player = null; tmp.delete() }
                mp.prepare(); mp.start()
                player = mp; playing = true
            }
        }) {
            Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Sesli mesaj", tint = MaterialTheme.colorScheme.primary)
        }
        Text("Sesli mesaj", color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FileBubble(name: String, b64: String, sizeHint: Int) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 260.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 2, fontSize = 14.sp)
            Text("~${sizeHint / 1365 + 1} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dosya", b64))
            Toast.makeText(context, "Dosya panoya kopyalandı.", Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Filled.Download, "Kopyala", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun ImageBubble(b64: String, onClick: () -> Unit) {
    val bmp = remember(b64) { decodeB64Bitmap(b64) }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp)
                .clickable(onClick = onClick)
                .padding(4.dp)
        )
    } else {
        Text("[fotoğraf]", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun ImageViewer(message: Message, onClose: () -> Unit, onDownload: (() -> Unit)?) {
    val bmp = remember(message.text) { decodeB64Bitmap(message.text) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offsetX += panChange.x
        offsetY += panChange.y
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY
                        )
                        .transformable(transformState)
                )
            } else {
                Text("İçerik yok", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Kapat", tint = Color.White) }
                Row {
                    if (scale > 1.05f) {
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Filled.Refresh, "Sığdır", tint = Color.White)
                        }
                    }
                    if (onDownload != null) {
                        IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "İndir", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockScreen() {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val biometricEnabled = Repo.settings.value?.biometricEnabled == true
    val canBiometric = remember(ctx) {
        BiometricManager.from(ctx)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    val prompt = remember(activity) {
        if (activity == null) null
        else BiometricPrompt(
            activity,
            androidx.core.content.ContextCompat.getMainExecutor(ctx),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Repo.unlock()
                }
            }
        )
    }

    fun showBiometric() {
        val p = prompt ?: return
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("nullnet kilidi")
                .setSubtitle("Devam etmek için doğrula")
                .setNegativeButtonText("PIN kullan")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        )
    }

    LaunchedEffect(Unit) {
        if (biometricEnabled && canBiometric) showBiometric()
    }

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("nullnet kilitli", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Devam etmek için PIN gir", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { new -> pin = new.filter { it.isDigit() }.take(12); error = false },
                label = { Text("PIN") },
                singleLine = true,
                isError = error,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (error) {
                Spacer(Modifier.height(6.dp))
                Text("Yanlış PIN.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    when (Repo.verifyPin(pin)) {
                        "ok" -> Repo.unlock()
                        "duress" -> { Repo.wipeAll() }
                        else -> { error = true; pin = "" }
                    }
                },
                enabled = pin.length >= 4,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aç") }
            if (biometricEnabled && canBiometric) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { showBiometric() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Parmak izi / Yüz ile aç")
                }
            }
        }
    }
}

/** Base64 görseli, kullanıcının seçtiği hedefe yazar. */
private fun saveBase64ToUri(context: Context, uri: Uri, b64: String) {
    runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

/** Seçilen görseli en fazla ~1280px olacak şekilde küçültüp JPEG base64 döndürür. */
private fun compressImageToBase64(context: Context, uri: Uri): String? {
    return try {
        val cr = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 1280) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

/** Avatar için daha küçük (~256px) sıkıştırma. */
private fun compressAvatarToBase64(context: Context, uri: Uri): String? {
    return try {
        val cr = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > 256) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

private fun statusSuffix(m: Message): String {
    if (!m.outgoing) return ""
    return when (m.status) {
        MessageStatus.PENDING -> " · gönderiliyor"
        MessageStatus.SENT -> " · gönderildi"
        MessageStatus.DELIVERED -> " · iletildi"
        MessageStatus.READ -> " · okundu"
        MessageStatus.FAILED -> " · gönderilemedi"
    }
}

private fun previewOf(m: Message): String = when (m.type) {
    "image" -> "📷 Fotoğraf"
    "audio" -> "🎤 Sesli mesaj"
    "file" -> "📎 " + m.fileName.ifBlank { "Dosya" }
    else -> m.text
}

private fun formatDuration(secs: Int): String =
    "%d:%02d".format(secs / 60, secs % 60)

/** Uri'den en fazla maxBytes okuyup (bytes, dosyaAdı) döndürür; aşılırsa null. */
private fun readUriBytes(context: Context, uri: Uri, maxBytes: Int): Pair<ByteArray, String>? {
    return try {
        val name = queryDisplayName(context, uri) ?: "dosya"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > maxBytes) null else bytes to name
    } catch (e: Exception) {
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val invite = remember { Repo.myInvite() }
    var paste by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kişi ekle") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("1) Kendi davet kodun", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Bunu güvendiğin bir kanaldan arkadaşına gönder. Kodu gören herkes sana yazabilir.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                SelectionContainer {
                    Text(invite, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(invite)) }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Kopyala")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { shareText(context, invite) }) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paylaş")
                }
            }

            Spacer(Modifier.height(28.dp))
            Text("2) Arkadaşının davet kodunu yapıştır", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = paste,
                onValueChange = { paste = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("GIZLI1.…") }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val r = Repo.addContactFromInvite(paste)
                    result = r.fold(
                        onSuccess = { "Kişi eklendi. Selamlaşma gönderildi." },
                        onFailure = { "Hata: ${it.message}" }
                    )
                    if (r.isSuccess) paste = ""
                },
                enabled = paste.isNotBlank()
            ) { Text("Kişiyi ekle") }
            result?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit, onWiped: () -> Unit) {
    val context = LocalContext.current
    val settings by Repo.settings.collectAsState()
    val identity by Repo.identity.collectAsState()
    var relay by remember { mutableStateOf(settings?.relay ?: "") }
    var name by remember { mutableStateOf(identity?.displayName ?: "") }
    var bio by remember { mutableStateOf(identity?.bio ?: "") }
    var avatar by remember { mutableStateOf(identity?.avatarB64 ?: "") }
    var transport by remember { mutableStateOf(settings?.transport ?: "mqtt") }
    var mqttHost by remember { mutableStateOf(settings?.mqttHost ?: "broker.hivemq.com") }
    var mqttPort by remember { mutableStateOf((settings?.mqttPort ?: 8883).toString()) }
    var mqttTls by remember { mutableStateOf(settings?.mqttTls ?: true) }
    var confirmWipe by remember { mutableStateOf(false) }
    var useTor by remember { mutableStateOf(settings?.useTor ?: false) }
    var torHost by remember { mutableStateOf(settings?.torHost ?: "127.0.0.1") }
    var torPort by remember { mutableStateOf((settings?.torPort ?: 9050).toString()) }
    var lockPin by remember { mutableStateOf("") }
    var lockDuress by remember { mutableStateOf("") }
    var lockCurrent by remember { mutableStateOf("") }
    var lockMsg by remember { mutableStateOf<String?>(null) }
    var backupPw by remember { mutableStateOf("") }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = compressAvatarToBase64(context, uri)
            if (b64 != null) avatar = b64
        }
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = Repo.exportBackup(backupPw)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                backupMsg = "Yedek kaydedildi (${bytes.size / 1024} KB)."
            }.onFailure { backupMsg = "Hata: ${it.message}" }
        }
    }
    val backupOpener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("Okunamadı")
                Repo.importBackup(bytes, backupPw)
            }.onSuccess { backupMsg = "Yedek geri yüklendi." }
                .onFailure { backupMsg = "Hata: ${it.message}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text("Profil", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                    AvatarB64(b64 = avatar, fallback = name.take(1).uppercase().ifBlank { "?" }, size = 72.dp)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PhotoCamera, "Fotoğraf seç", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Görünecek ad") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bio, onValueChange = { bio = it },
                        label = { Text("Hakkımda (bio)") }, maxLines = 3, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (name.isNotBlank()) Repo.setDisplayName(name.trim())
                    Repo.setProfile(avatar, bio.trim())
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Profili kaydet ve yayınla") }

            Spacer(Modifier.height(24.dp))
            Text("Tema", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            com.gizli.messenger.ui.themeOptions.forEach { opt ->
                Row(
                    Modifier.fillMaxWidth().clickable { Repo.setTheme(opt.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (settings?.theme == opt.id) Icons.Filled.Check else Icons.Filled.Edit,
                        null, Modifier.size(18.dp),
                        tint = if (settings?.theme == opt.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(opt.label, fontWeight = if (settings?.theme == opt.id) FontWeight.SemiBold else FontWeight.Normal)
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ekran görüntüsünü engelle", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Açıkken ekran görüntüsü ve kayıt engellenir (FLAG_SECURE).",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings?.secureScreen == true,
                    onCheckedChange = { Repo.setSecureScreen(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("Uygulama kilidi", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (settings?.lockEnabled == true) "Kilit açık. Uygulama arka plana atıldıktan sonra PIN ister."
                else "Açıkken uygulama PIN (veya biyometrik) ister. Zorlama PIN'i girilirse tüm veriler silinir.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lockPin, onValueChange = { lockPin = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text(if (settings?.lockEnabled == true) "Yeni PIN (boş = değiştirme)" else "PIN") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = lockDuress, onValueChange = { lockDuress = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text("Zorlama (duress) PIN — isteğe bağlı") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        if (lockPin.length >= 4) {
                            Repo.setLock(lockPin, lockDuress.ifBlank { null })
                            lockPin = ""; lockDuress = ""; lockMsg = "Kilit ayarlandı."
                        } else lockMsg = "PIN en az 4 haneli olmalı."
                    },
                    enabled = lockPin.length >= 4,
                    modifier = Modifier.weight(1f)
                ) { Text(if (settings?.lockEnabled == true) "Kilidi güncelle" else "Kilidi aç") }
                if (settings?.lockEnabled == true) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        if (Repo.verifyPin(lockCurrent) == "ok") { Repo.setLock(null, null); lockCurrent = ""; lockMsg = "Kilit kaldırıldı." }
                        else lockMsg = "Kaldırmak için mevcut PIN'i gir."
                    }, modifier = Modifier.weight(1f)) { Text("Kilidi kaldır") }
                }
            }
            if (settings?.lockEnabled == true) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lockCurrent, onValueChange = { lockCurrent = it.filter { c -> c.isDigit() } },
                    label = { Text("Mevcut PIN (kaldırmak için)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            lockMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
            Spacer(Modifier.height(12.dp))
            val canBio = remember(context) {
                BiometricManager.from(context)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biyometrik ile aç", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (canBio) "Parmak izi / yüz ile kilidi aç. Ayarlıysa PIN yerine kullanılır."
                        else "Bu cihazda biyometrik yok.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings?.biometricEnabled == true,
                    onCheckedChange = { Repo.setBiometric(it) },
                    enabled = canBio && settings?.lockEnabled == true
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tor üzerinden bağlan", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Mesajlar Orbot (SOCKS5) üzerinden Tor ağına taşınır. Orbot kurulu ve açık olmalı.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useTor, onCheckedChange = { useTor = it })
            }
            if (useTor) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = torHost, onValueChange = { torHost = it }, label = { Text("SOCKS host") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = torPort, onValueChange = { new -> torPort = new.filter { it.isDigit() } }, label = { Text("Port") },
                        singleLine = true, modifier = Modifier.width(110.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Şifreli yedek", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tüm mesajların ve kimliğin parola ile şifrelenip tek dosyaya yazılır. Parolayı unutma!",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = backupPw, onValueChange = { backupPw = it },
                label = { Text("Yedek parolası") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { backupSaver.launch("nullnet_yedek.ngbk") },
                    enabled = backupPw.length >= 4, modifier = Modifier.weight(1f)
                ) { Text("Yedek al") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { backupOpener.launch(arrayOf("*/*")) },
                    enabled = backupPw.length >= 4, modifier = Modifier.weight(1f)
                ) { Text("Geri yükle") }
            }
            backupMsg?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }

            Spacer(Modifier.height(24.dp))
            Text("Aktarma yöntemi", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { transport = "mqtt" },
                    modifier = Modifier.weight(1f),
                    border = if (transport == "mqtt") androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) { Text("Hazır (MQTT)") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { transport = "relay" },
                    modifier = Modifier.weight(1f),
                    border = if (transport == "relay") androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) { Text("Kendi relay'im") }
            }

            Spacer(Modifier.height(12.dp))
            if (transport == "mqtt") {
                OutlinedTextField(
                    value = mqttHost, onValueChange = { mqttHost = it },
                    label = { Text("MQTT broker adresi") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = mqttPort, onValueChange = { new -> mqttPort = new.filter { it.isDigit() } },
                        label = { Text("Port") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("TLS", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = mqttTls, onCheckedChange = { mqttTls = it })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hazır (ücretsiz) broker: broker.hivemq.com (TLS 8883). Kendi sunucun için adını yaz. " +
                        "Mesajlar uçtan uca şifreli; broker içeriği göremez.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = relay, onValueChange = { relay = it },
                    label = { Text("Relay sunucu adresi") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Örn: https://senin-sunucun.örnek  (kendi kurduğun şifreli aktarma sunucusu)",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (name.isNotBlank()) Repo.setDisplayName(name.trim())
                if (relay.isNotBlank()) Repo.setRelay(relay.trim())
                Repo.setMqtt(mqttHost.trim().ifBlank { "broker.hivemq.com" }, mqttPort.toIntOrNull() ?: 8883, mqttTls)
                Repo.setTor(useTor, torHost.trim().ifBlank { "127.0.0.1" }, torPort.toIntOrNull() ?: 9050)
                Repo.setTransport(transport)
                Repo.disconnectMqtt()
                if (transport == "mqtt") Repo.connectMqtt()
                onBack()
            }, modifier = Modifier.fillMaxWidth()) { Text("Kaydet ve bağlan") }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Arka planda kontrol", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Mesajları arka planda yoklar. Pil tüketimini artırır.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings?.backgroundPolling == true,
                    onCheckedChange = { on ->
                        Repo.setBackgroundPolling(on)
                        if (on) PollService.start(context) else PollService.stop(context)
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Açılışta güncellemeyi kontrol et", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Uygulamaya ilk girişte imzalı yeni sürüm varsa indirme bildirimi gösterilir.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings?.autoUpdate == true,
                    onCheckedChange = { Repo.setAutoUpdate(it) }
                )
            }

            Spacer(Modifier.height(24.dp))
            Text("Kimlik (anonim)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(identity?.userId ?: "", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(24.dp))
            Text("Tehlikeli alan", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            if (!confirmWipe) {
                OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Tüm verileri sil ve kimliği yenile")
                }
            } else {
                Text(
                    "Bu işlem tüm mesaj ve kişileri siler, YENİ bir anonim kimlik üretir. Eski kişiler sana ulaşamaz.",
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { confirmWipe = false }) { Text("Vazgeç") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { Repo.wipeAll(); onWiped() }) { Text("Evet, sil") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val um = remember { UpdateManager(context) }
    var status by remember { mutableStateOf("Güncelleme kaynağı:\n${BuildConfig.UPDATE_MANIFEST_URL}") }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }

    fun check() {
        scope.launch {
            busy = true; status = "Kontrol ediliyor…"
            val r = um.checkForUpdate()
            r.fold(
                onSuccess = { info ->
                    update = info
                    status = if (info == null) "Uygulama güncel (v${BuildConfig.VERSION_NAME})."
                    else "Yeni sürüm: v${info.versionName} (kod ${info.versionCode})\n\n${info.notes}"
                },
                onFailure = { status = "Hata: ${it.message}" }
            )
            busy = false
        }
    }

    fun downloadAndInstall() {
        val info = update ?: return
        scope.launch {
            busy = true; progress = 0; status = "İndiriliyor…"
            val r = um.downloadApk(info) { progress = it }
            r.fold(
                onSuccess = { file ->
                    status = "İndirme doğrulandı. Kurulum açılıyor…"
                    if (um.canInstallPackages()) um.install(file)
                    else {
                        status = "Kurulum izni gerekiyor. Açılan ekrandan 'Bu kaynağa izin ver' seç."
                        um.openInstallPermissionSettings()
                    }
                },
                onFailure = { status = "Hata: ${it.message}" }
            )
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Güncelleme") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
            Text("Güvenli güncelleme", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Güncelleme paketi Ed25519 imzası ve APK sertifikası ile doğrulanır; sahte sürüm kurulamaz.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text(status, fontSize = 13.sp)
            if (busy && progress in 1..99) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { check() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Güncellemeyi kontrol et")
            }
            if (update != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { downloadAndInstall() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("İndir ve kur")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoScreen(
    group: Group,
    contacts: List<Contact>,
    myId: String,
    onBack: () -> Unit,
    onUpdate: (String, String) -> Unit,
    onAddMembers: (List<String>) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(group.name) }
    var avatar by remember { mutableStateOf(group.avatarB64) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = compressAvatarToBase64(context, uri)
            if (b64 != null) {
                avatar = b64
                onUpdate(name, b64)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grup bilgisi") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                    AvatarB64(b64 = avatar, fallback = name.take(1).uppercase().ifBlank { "G" }, size = 96.dp)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp).align(Alignment.BottomEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PhotoCamera, "Grup fotoğrafı", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Grup fotoğrafını değiştir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Grup adı") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onUpdate(name, avatar) }, modifier = Modifier.fillMaxWidth()) {
                Text("Grubu kaydet ve yayınla")
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Üyeler (${group.members.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Üye ekle")
                }
            }
            Spacer(Modifier.height(8.dp))
            group.members.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarB64(
                        b64 = contacts.firstOrNull { it.userId == m.userId }?.avatarB64
                            ?: if (m.userId == myId) Repo.identity.value?.avatarB64 ?: "" else "",
                        fallback = m.name.take(1).uppercase().ifBlank { "?" },
                        size = 40.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(m.name + if (m.userId == myId) " (sen)" else "", modifier = Modifier.weight(1f))
                    if (m.userId != myId) {
                        IconButton(onClick = { onRemoveMember(m.userId) }) {
                            Icon(Icons.Filled.Close, "Çıkar", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(24.dp))
            if (!confirmDelete) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Gruptan ayrıl / sil")
                }
            } else {
                Text("Grubu silersen bu grup cihazından kaldırılır.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { confirmDelete = false }) { Text("Vazgeç") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDelete) { Text("Evet, sil") }
                }
            }
        }
    }

    if (showAdd) {
        val memberIds = group.members.map { it.userId }.toSet()
        val candidates = contacts.filter { !memberIds.contains(it.userId) }
        AddMembersDialog(
            candidates = candidates,
            onDismiss = { showAdd = false },
            onConfirm = { ids -> onAddMembers(ids); showAdd = false }
        )
    }
}

@Composable
private fun AddMembersDialog(
    candidates: List<Contact>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val selected = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Üye ekle") },
        text = {
            if (candidates.isEmpty()) {
                Text("Eklenecek yeni kişi yok.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected.value = if (selected.value.contains(c.userId))
                                    selected.value - c.userId else selected.value + c.userId
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.value.contains(c.userId),
                                onCheckedChange = { on ->
                                    selected.value = if (on) selected.value + c.userId else selected.value - c.userId
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(c.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.value.toList()) },
                enabled = selected.value.isNotEmpty()
            ) { Text("Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>, String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = compressAvatarToBase64(context, uri)
            if (b64 != null) avatar = b64
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni grup") },
        text = {
            Column {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                        AvatarB64(b64 = avatar, fallback = name.take(1).uppercase().ifBlank { "G" }, size = 72.dp)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp).align(Alignment.BottomEnd)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.PhotoCamera, "Grup fotoğrafı", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Grup fotoğrafı (isteğe bağlı)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Grup adı") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                if (contacts.isEmpty()) {
                    Text("Önce kişi eklemelisin.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    Text("Üyeler", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        contacts.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected.value = if (selected.value.contains(c.userId))
                                        selected.value - c.userId else selected.value + c.userId
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected.value.contains(c.userId),
                                    onCheckedChange = { on ->
                                        selected.value = if (on) selected.value + c.userId else selected.value - c.userId
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(c.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), selected.value.toList(), avatar) },
                enabled = name.isNotBlank()
            ) { Text("Oluştur") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Daveti paylaş"))
}

private fun timeText(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun dayKey(ts: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(ts))

private fun sameDay(a: Long, b: Long): Boolean = dayKey(a) == dayKey(b)

private fun dayText(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Bugün"
        sameDay(ts, now - 86_400_000L) -> "Dün"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}
