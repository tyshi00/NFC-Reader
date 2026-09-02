package com.lightcommunity.nfcreader

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the credential-bearing action fields (webhook URL, headers, body,
 * note, dial number) at rest with an Android Keystore AES-256-GCM key.
 * If the keystore is unavailable the values are stored as-is rather than lost.
 */
object CredentialCipher {

    private const val TAG = "NfcSecret"

    private val cipher: SecretCipher? by lazy {
        runCatching { SecretCipher() }
            .onFailure { Log.w(TAG, "keystore unavailable; action fields stored in the clear") }
            .getOrNull()
    }

    /** Plaintext -> stored form. Blank/null pass through. */
    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val c = cipher ?: return plain
        return runCatching { Base64.getEncoder().encodeToString(c.encrypt(plain)) }
            .getOrDefault(plain)
    }

    /** Stored form -> plaintext. A legacy plaintext or undecryptable value is returned unchanged. */
    fun open(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        val c = cipher ?: return stored
        return runCatching { c.decrypt(Base64.getDecoder().decode(stored)) }
            .getOrDefault(stored)
    }
}

private class SecretCipher {

    private val key: SecretKey

    init {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        key = if (keyStore.containsAlias(KEY_ALIAS)) {
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(cipher.iv.size + body.size).put(cipher.iv).put(body).array()
    }

    fun decrypt(blob: ByteArray): String {
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(GCM_IV_LENGTH).also { buffer.get(it) }
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nfc_reader_secret"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
