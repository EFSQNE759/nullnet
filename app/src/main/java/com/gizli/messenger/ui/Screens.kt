package com.gizli.messenger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Screen {
    data object Home : Screen
    data class Chat(val contactId: String) : Screen
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

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showCreateGroup by remember { mutableStateOf(false) }

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

    GizliTheme {
        val id = identity
        when {
            id == null -> CenterLoading()
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
                        ChatScreen(
                            title = group?.name ?: contact?.name ?: "Sohbet",
                            isGroup = group != null,
                            messages = allMessages[chatId].orEmpty(),
                            nameOf = { uid ->
                                if (uid == Repo.identity.value?.userId) "Sen"
                                else Repo.contactById(uid)?.name ?: uid.take(8)
                            },
                            onBack = { screen = Screen.Home },
                            onSend = { t ->
                                if (group != null) Repo.sendGroupMessage(chatId, t) else Repo.sendMessage(chatId, t)
                            },
                            onSendImage = { b64 ->
                                if (group != null) Repo.sendGroupMessage(chatId, b64, "image")
                                else Repo.sendMessage(chatId, b64, "image")
                            }
                        )
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
                        onCreate = { gname, ids ->
                            Repo.createGroup(gname, ids)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("nullnet") },
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
                if (groups.isNotEmpty()) {
                    item { SectionHeader("Gruplar") }
                    items(groups, key = { it.id }) { g ->
                        val last = allMessages[g.id]?.lastOrNull()
                        ConversationRow(
                            title = g.name,
                            subtitle = preview(last),
                            avatarText = g.name.take(1).uppercase(),
                            isGroup = true,
                            onClick = { onOpen(g.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (contacts.isNotEmpty()) {
                    item { SectionHeader("Kişiler") }
                    items(contacts, key = { it.userId }) { c ->
                        val last = allMessages[c.userId]?.lastOrNull()
                        ConversationRow(
                            title = c.name,
                            subtitle = preview(last) ?: (c.userId.take(12) + "…"),
                            avatarText = c.name.take(1).uppercase(),
                            isGroup = false,
                            onClick = { onOpen(c.userId) }
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

@Composable
private fun ConversationRow(
    title: String,
    subtitle: String?,
    avatarText: String,
    isGroup: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(avatarText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGroup) {
                    Icon(Icons.Filled.Group, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    title: String,
    isGroup: Boolean,
    messages: List<Message>,
    nameOf: (String) -> String,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val b64 = compressImageToBase64(context, uri)
            if (b64 != null) onSendImage(b64)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Geri") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(
                        m = m,
                        senderName = if (isGroup && !m.outgoing) nameOf(m.senderId) else null
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { picker.launch("image/*") }) {
                    Icon(Icons.Filled.Image, "Fotoğraf", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mesaj yaz…") },
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } },
                    enabled = text.isNotBlank()
                ) { Icon(Icons.Filled.Send, "Gönder", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: Message, senderName: String?) {
    val isOut = m.outgoing
    val bubbleColor = if (isOut) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else MaterialTheme.colorScheme.surfaceVariant
    val align = if (isOut) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(horizontalAlignment = if (isOut) Alignment.End else Alignment.Start) {
            if (senderName != null) {
                Text(
                    senderName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Surface(color = bubbleColor, shape = RoundedCornerShape(14.dp)) {
                if (m.type == "image") {
                    ImageBubble(m.text)
                } else {
                    Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
private fun ImageBubble(b64: String) {
    val bmp = remember(b64) {
        runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp).padding(4.dp)
        )
    } else {
        Text("[fotoğraf]", Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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

private fun statusSuffix(m: Message): String = when (m.status) {
    MessageStatus.PENDING -> " · gönderiliyor"
    MessageStatus.FAILED -> " · gönderilemedi"
    else -> ""
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
    var transport by remember { mutableStateOf(settings?.transport ?: "mqtt") }
    var mqttHost by remember { mutableStateOf(settings?.mqttHost ?: "broker.hivemq.com") }
    var mqttPort by remember { mutableStateOf((settings?.mqttPort ?: 8883).toString()) }
    var mqttTls by remember { mutableStateOf(settings?.mqttTls ?: true) }
    var confirmWipe by remember { mutableStateOf(false) }

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
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Görünecek ad") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
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

@Composable
private fun CreateGroupDialog(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni grup") },
        text = {
            Column {
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
                onClick = { onCreate(name.trim(), selected.value.toList()) },
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
