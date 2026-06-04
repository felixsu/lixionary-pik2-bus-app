package com.lixionary.pik2bus.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class EncryptedPrefsManager(context: Context) {
    companion object {
        private const val SECURE_PREFS_FILE = "secure_bus_tracker_prefs"
        private const val KEY_API_KEY = "api_key"
    }

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val sharedPrefs = EncryptedSharedPreferences.create(
        SECURE_PREFS_FILE,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(): String {
        return sharedPrefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun saveApiKey(apiKey: String) {
        sharedPrefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun clearApiKey() {
        sharedPrefs.edit().remove(KEY_API_KEY).apply()
    }
}
