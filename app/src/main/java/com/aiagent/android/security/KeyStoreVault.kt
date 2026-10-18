package com.aiagent.android.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyStoreVault(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "AIAgentMasterSecretKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        private const val PREFS_NAME = "ai_agent_secure_prefs"
        private const val KEY_ENCRYPTED_API_KEY = "enc_api_key"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_SCREEN_ASSISTANT_ENABLED = "screen_assistant_enabled"
        private const val KEY_OFFLINE_PREFERRED = "offline_preferred"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Combine IV (12 bytes) + CipherText
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isEmpty()) return ""
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun saveApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey)
        prefs.edit().putString(KEY_ENCRYPTED_API_KEY, encrypted).apply()
    }

    fun getApiKey(): String {
        val encrypted = prefs.getString(KEY_ENCRYPTED_API_KEY, null) ?: return ""
        return decrypt(encrypted)
    }

    fun hasApiKey(): Boolean = getApiKey().isNotEmpty()

    fun saveBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim().removeSuffix("/")).apply()
    }

    fun getBaseUrl(): String {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun saveModelName(model: String) {
        prefs.edit().putString(KEY_MODEL_NAME, model.trim()).apply()
    }

    fun getModelName(): String {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun isScreenAssistantEnabled(): Boolean {
        return prefs.getBoolean(KEY_SCREEN_ASSISTANT_ENABLED, false)
    }

    fun setScreenAssistantEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_ASSISTANT_ENABLED, enabled).apply()
    }

    fun isOfflinePreferred(): Boolean {
        return prefs.getBoolean(KEY_OFFLINE_PREFERRED, false)
    }

    fun setOfflinePreferred(preferred: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_PREFERRED, preferred).apply()
    }

    // ==========================================
    // 永久长时记忆模块 (AES-256-GCM 硬件加密存储)
    // ==========================================

    private val memoryKeysPref = "user_memory_all_keys"

    /**
     * 保存一条长时记忆（硬件级加密，跨会话/开新对话永久保留）
     */
    fun saveMemory(key: String, value: String, label: String = "") {
        val cleanKey = key.trim().lowercase()
        val encValue = encrypt(value.trim())
        val displayLabel = label.ifBlank { key }
        val now = System.currentTimeMillis()

        val allKeys = getMemoryKeys().toMutableSet()
        allKeys.add(cleanKey)

        prefs.edit()
            .putStringSet(memoryKeysPref, allKeys)
            .putString("mem_val_$cleanKey", encValue)
            .putString("mem_lbl_$cleanKey", displayLabel)
            .putLong("mem_time_$cleanKey", now)
            .apply()
    }

    /**
     * 根据键名获取解密后的长时记忆内容
     */
    fun getMemory(key: String): String? {
        val cleanKey = key.trim().lowercase()
        val enc = prefs.getString("mem_val_$cleanKey", null) ?: return null
        val decrypted = decrypt(enc)
        return decrypted.ifEmpty { null }
    }

    /**
     * 获取所有永久长时记忆列表（已解密）
     */
    fun getAllMemories(): List<com.aiagent.android.model.UserMemoryItem> {
        val keys = getMemoryKeys()
        val list = mutableListOf<com.aiagent.android.model.UserMemoryItem>()
        for (k in keys) {
            val enc = prefs.getString("mem_val_$k", null) ?: continue
            val value = decrypt(enc)
            val label = prefs.getString("mem_lbl_$k", k) ?: k
            val time = prefs.getLong("mem_time_$k", 0L)
            list.add(com.aiagent.android.model.UserMemoryItem(key = k, value = value, label = label, updatedTime = time))
        }
        return list.sortedByDescending { it.updatedTime }
    }

    /**
     * 删除指定长时记忆
     */
    fun deleteMemory(key: String) {
        val cleanKey = key.trim().lowercase()
        val allKeys = getMemoryKeys().toMutableSet()
        allKeys.remove(cleanKey)
        prefs.edit()
            .putStringSet(memoryKeysPref, allKeys)
            .remove("mem_val_$cleanKey")
            .remove("mem_lbl_$cleanKey")
            .remove("mem_time_$cleanKey")
            .apply()
    }

    /**
     * 清空所有长时记忆
     */
    fun clearAllMemories() {
        val keys = getMemoryKeys()
        val editor = prefs.edit()
        for (k in keys) {
            editor.remove("mem_val_$k")
            editor.remove("mem_lbl_$k")
            editor.remove("mem_time_$k")
        }
        editor.remove(memoryKeysPref).apply()
    }

    private fun getMemoryKeys(): Set<String> {
        return prefs.getStringSet(memoryKeysPref, emptySet()) ?: emptySet()
    }
}
