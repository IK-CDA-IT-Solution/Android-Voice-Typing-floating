package com.nirzor.voicebubble.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyManager private constructor(
    context: Context,
    private val secretKeyProvider: () -> SecretKey
) {

    constructor(context: Context) : this(context, { SecureKeyManager.createSecretKey() })

    // Injectable only for unit tests so both production catch paths can be exercised safely.
    internal constructor(
        context: Context,
        secretKeyProvider: () -> SecretKey,
        @Suppress("UNUSED_PARAMETER") testHook: Unit
    ) : this(context, secretKeyProvider)

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gemini_api_key_aes"
        private const val PREFS_NAME = "secure_gemini_prefs"
        private const val PREF_KEY_CIPHERTEXT = "encrypted_gemini_key"
        private const val PREF_KEY_IV = "gemini_key_iv"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private fun createSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val keySpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()

                keyGenerator.init(keySpec)
                keyGenerator.generateKey()
            }
            val secretKeyEntry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            return secretKeyEntry.secretKey
        }

        @Volatile
        private var INSTANCE: SecureKeyManager? = null

        fun getInstance(context: Context): SecureKeyManager {
            return INSTANCE ?: synchronized(this) {
                val instance = SecureKeyManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    private fun saveKey(apiKey: String) {
        try {
            val secretKey = secretKeyProvider()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)

            prefs.edit()
                .putString(PREF_KEY_IV, ivB64)
                .putString(PREF_KEY_CIPHERTEXT, cipherB64)
                .apply()
        } catch (_: Exception) {
            Log.e(TAG, "Secure key operation failed category=save")
        }
    }

    private fun readKey(): String? {
        return try {
            val ivB64 = prefs.getString(PREF_KEY_IV, null) ?: return null
            val cipherB64 = prefs.getString(PREF_KEY_CIPHERTEXT, null) ?: return null
            val secretKey = secretKeyProvider()
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(cipherB64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            Log.e(TAG, "Secure key operation failed category=get")
            null
        }
    }

    fun saveGeminiApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            clearGeminiApiKey()
            return
        }
        saveKey(apiKey)
    }

    fun getGeminiApiKey(): String? = readKey()

    fun clearGeminiApiKey() {
        prefs.edit()
            .remove(PREF_KEY_IV)
            .remove(PREF_KEY_CIPHERTEXT)
            .apply()
    }
}
