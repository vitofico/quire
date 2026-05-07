package io.theficos.ereader.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CalibreCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): CalibreCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return CalibreCredentials(baseUrl, user, pass)
    }

    fun put(creds: CalibreCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, creds.baseUrl)
            .putString(KEY_USER, creds.username)
            .putString(KEY_PASS, creds.password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
