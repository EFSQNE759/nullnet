package com.gizli.messenger.data

data class Identity(
    val userId: String,
    val encPublic: String,
    val encPrivate: String,
    val displayName: String,
    val avatarB64: String = "",
    val bio: String = ""
)

data class Contact(
    val userId: String,
    val publicKey: String,
    val name: String,
    val relay: String,
    val addedAt: Long,
    val pending: Boolean = false,
    val avatarB64: String = "",
    val bio: String = ""
)

data class Message(
    val id: String,
    val contactId: String,
    val outgoing: Boolean,
    val text: String,
    val ts: Long,
    val status: MessageStatus,
    val type: String = "text",
    val groupId: String = "",
    val senderId: String = "",
    val viewOnce: Boolean = false,
    val viewed: Boolean = false
)

enum class MessageStatus { PENDING, SENT, DELIVERED, FAILED }

data class GroupMember(
    val userId: String,
    val publicKey: String,
    val name: String
)

data class Group(
    val id: String,
    val name: String,
    val keyB64: String,
    val members: List<GroupMember>,
    val createdAt: Long,
    val avatarB64: String = ""
)

data class Settings(
    val relay: String,
    val displayName: String,
    val backgroundPolling: Boolean,
    val transport: String = "mqtt",
    val mqttHost: String = "broker.hivemq.com",
    val mqttPort: Int = 8883,
    val mqttTls: Boolean = true,
    val theme: String = "default",
    val secureScreen: Boolean = true
)
