package io.theficos.ereader.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CalibreCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<CalibreCredentials?> = _flow.asStateFlow()

    fun get(): CalibreCredentials? = _flow.value

    fun put(creds: CalibreCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, creds.baseUrl)
            .putString(KEY_USER, creds.username)
            .putString(KEY_PASS, creds.password)
            .commit()
        _flow.value = creds
    }

    fun clear() {
        prefs.edit().clear().commit()
        _flow.value = null
    }

    private fun load(): CalibreCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return CalibreCredentials(baseUrl, user, pass)
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
