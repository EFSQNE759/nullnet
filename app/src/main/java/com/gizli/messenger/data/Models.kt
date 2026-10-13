package com.gizli.messenger.data

data class Identity(
    val userId: String,
    val encPublic: String,
    val encPrivate: String,
    val displayName: String
)

data class Contact(
    val userId: String,
    val publicKey: String,
    val name: String,
    val relay: String,
    val addedAt: Long,
    val pending: Boolean = false
)

data class Message(
    val id: String,
    val contactId: String,
    val outgoing: Boolean,
    val text: String,
    val ts: Long,
    val status: MessageStatus
)

enum class MessageStatus { PENDING, SENT, DELIVERED, FAILED }

data class Settings(
    val relay: String,
    val displayName: String,
    val backgroundPolling: Boolean,
    val transport: String = "mqtt",
    val mqttHost: String = "broker.hivemq.com",
    val mqttPort: Int = 8883,
    val mqttTls: Boolean = true
)
