package com.homekey.tv.data.local

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

object TinkKeystoreCrypto {
    private const val TAG = "TinkKeystoreCrypto"
    private const val KEYSET_NAME = "ha_tv_tink_keyset"
    private const val PREF_FILE_NAME = "ha_tv_tink_pref"
    private const val MASTER_KEY_URI = "android-keystore://ha_tv_tink_master_key"

    @Volatile
    private var cachedAead: Aead? = null

    fun getAead(context: Context): Aead? {
        val existing = cachedAead
        if (existing != null) return existing
        synchronized(this) {
            val doubleCheck = cachedAead
            if (doubleCheck != null) return doubleCheck
            return try {
                AeadConfig.register()
                val keysetHandle = AndroidKeysetManager.Builder()
                    .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle
                val primitive = keysetHandle.getPrimitive(Aead::class.java)
                cachedAead = primitive
                primitive
            } catch (e: Exception) {
                Log.w(TAG, "Android Keystore master key unavailable, attempting un-mastered keyset: ${e.message}")
                try {
                    val fallbackHandle = AndroidKeysetManager.Builder()
                        .withSharedPref(context.applicationContext, "${KEYSET_NAME}_fallback", "${PREF_FILE_NAME}_fallback")
                        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                        .build()
                        .keysetHandle
                    val primitive = fallbackHandle.getPrimitive(Aead::class.java)
                    cachedAead = primitive
                    primitive
                } catch (e2: Exception) {
                    Log.e(TAG, "Tink initialization completely failed: ${e2.message}")
                    null
                }
            }
        }
    }

    fun encrypt(context: Context, plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val crypto = getAead(context) ?: return plaintext
        return try {
            val ciphertext = crypto.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed, storing plaintext fallback: ${e.message}")
            plaintext
        }
    }

    fun decrypt(context: Context, ciphertextBase64: String): String {
        if (ciphertextBase64.isEmpty()) return ""
        val crypto = getAead(context) ?: return ciphertextBase64
        return try {
            val raw = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            val decryptedBytes = crypto.decrypt(raw, null)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            // Decryption failed or value was already plaintext
            ciphertextBase64
        }
    }
}
