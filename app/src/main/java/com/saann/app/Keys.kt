package com.saann.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Keys {
    private var prefs: SharedPreferences? = null

    fun prefs(c: Context): SharedPreferences {
        val existing = prefs
        if (existing != null) return existing
        val master = MasterKey.Builder(c)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            c,
            "saann_secure_prefs",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs = p
        return p
    }

    fun get(c: Context, name: String): String? = prefs(c).getString(name, null)

    fun set(c: Context, name: String, value: String) {
        prefs(c).edit().putString(name, value).apply()
    }

    fun setFlag(c: Context, name: String, value: Boolean) {
        prefs(c).edit().putBoolean(name, value).apply()
    }

    fun flag(c: Context, name: String): Boolean = prefs(c).getBoolean(name, false)
}
