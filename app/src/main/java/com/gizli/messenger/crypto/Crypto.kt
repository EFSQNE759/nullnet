package com.gizli.messenger.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Uçtan uca şifreleme çekirdeği.
 *
 * Anahtar değişimi : X25519 (Curve25519 ECDH)
 * Anahtar türetme  : HKDF-SHA256
 * Şifreleme        : AES-256-GCM (kimlik doğrulamalı)
 * İmza (güncelleme): Ed25519
 */
object Crypto {

    private val rng = SecureRandom()
    const val MAC_BYTES = 16

    // ---------- Rastgelelik ----------

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        rng.nextBytes(b)
        return b
    }

    fun newUserId(): String = Base32.encode(randomBytes(16))

    // ---------- X25519 ----------

    /** (ozel, acik) anahtar cifti dondurur. */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(rng)
        val pub = priv.generatePublicKey()
        return priv.encoded to pub.encoded
    }

    fun publicFromPrivate(priv: ByteArray): ByteArray =
        X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(priv, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        return out
    }

    // ---------- HKDF ----------

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    // ---------- AES-256-GCM ----------

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    // ---------- Parola tabanlı anahtar (yedek) ----------

    /** PBKDF2-HMAC-SHA256 ile paroladan 32 baytlık anahtar türetir. */
    fun deriveKey(password: String, salt: ByteArray, iterations: Int = 120_000): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ---------- Ed25519 (güncelleme imzası doğrulama) ----------

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    // ---------- Mesaj zarflama ----------

    /**
     * Alıcının açık anahtarına şifreli zarf oluşturur.
     * shared = X25519(gonderen_ozel, alici_acik)
     * sharedE = X25519(gecici_ozel, alici_acik)
     * key = HKDF(shared || sharedE, salt, info)
     */
    fun sealFor(
        senderPriv: ByteArray,
        recipientPub: ByteArray,
        plaintext: ByteArray,
        info: ByteArray = INFO_MSG
    ): Sealed {
        val (ephPriv, ephPub) = generateKeyPair()
        val shared = x25519(senderPriv, recipientPub)
        val sharedE = x25519(ephPriv, recipientPub)
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val key = hkdf(shared + sharedE, salt, info, 32)
        val ct = aesGcmEncrypt(key, nonce, plaintext)
        return Sealed(
            ephemeralPub = ephPub,
            salt = salt,
            nonce = nonce,
            ciphertext = ct
        )
    }

    /**
     * Bir zarfı, gonderen acik anahtari ile acar.
     */
    fun openFrom(
        recipientPriv: ByteArray,
        senderPub: ByteArray,
        sealed: Sealed,
        info: ByteArray = INFO_MSG
    ): ByteArray {
        val shared = x25519(recipientPriv, senderPub)
        val sharedE = x25519(recipientPriv, sealed.ephemeralPub)
        val key = hkdf(shared + sharedE, sealed.salt, info, 32)
        return aesGcmDecrypt(key, sealed.nonce, sealed.ciphertext)
    }

    val INFO_MSG = "gizli-msg-v1".toByteArray(Charsets.UTF_8)
    val INFO_HELLO = "gizli-hello-v1".toByteArray(Charsets.UTF_8)

    data class Sealed(
        val ephemeralPub: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )
}
