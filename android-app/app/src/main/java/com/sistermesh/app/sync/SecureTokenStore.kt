package com.sistermesh.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredTokens(val accessToken: String, val refreshCookie: String)

interface TokenStore {
    fun read(): StoredTokens?
    fun write(tokens: StoredTokens)
    fun clear()
}

class AndroidKeystoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): StoredTokens? {
        val encoded = preferences.getString(ENCRYPTED_TOKENS, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_LENGTH)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val encrypted = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
            StoredTokens(
                accessToken = json.getString(ACCESS_TOKEN).takeIf(String::isNotBlank) ?: return null,
                refreshCookie = json.getString(REFRESH_COOKIE).takeIf(String::isNotBlank) ?: return null
            )
        } catch (_: Exception) {
            preferences.edit().remove(ENCRYPTED_TOKENS).commit()
            null
        }
    }

    @Synchronized
    override fun write(tokens: StoredTokens) {
        require(tokens.accessToken.isNotBlank() && tokens.refreshCookie.isNotBlank())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plaintext = JSONObject()
            .put(ACCESS_TOKEN, tokens.accessToken)
            .put(REFRESH_COOKIE, tokens.refreshCookie)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val encrypted = cipher.doFinal(plaintext)
        val packed = ByteArrayOutputStream().use { output ->
            output.write(cipher.iv)
            output.write(encrypted)
            output.toByteArray()
        }
        check(
            preferences.edit()
                .putString(ENCRYPTED_TOKENS, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit()
        ) { "Secure session could not be saved." }
    }

    @Synchronized
    override fun clear() {
        preferences.edit().remove(ENCRYPTED_TOKENS).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES = "sistermesh_secure_session"
        private const val ENCRYPTED_TOKENS = "encrypted_tokens"
        private const val ACCESS_TOKEN = "accessToken"
        private const val REFRESH_COOKIE = "refreshCookie"
        private const val KEY_ALIAS = "sistermesh.auth.tokens.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
