package com.gizli.messenger.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cihazdaki gizli veriyi (kimlik özel anahtarı, kişiler, mesajlar) Android Keystore
 * içindeki, dışa aktarılamayan bir AES anahtarı ile şifreleyerek saklar.
 */
object SecureStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gizli_master_key_v1"
    private const val GCM_TAG_BITS = 128

    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance(KEYSTORE)
        ks.load(null)
        return ks
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val ivLen = payload[0].toInt() and 0xFF
        val iv = payload.copyOfRange(1, 1 + ivLen)
        val ct = payload.copyOfRange(1 + ivLen, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    fun writeSecure(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(encrypt(data))
    }

    fun readSecure(file: File): ByteArray? {
        if (!file.exists()) return null
        return try {
            decrypt(file.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    fun writeSecureString(file: File, text: String) = writeSecure(file, text.toByteArray(Charsets.UTF_8))
    fun readSecureString(file: File): String? = readSecure(file)?.toString(Charsets.UTF_8)
}
