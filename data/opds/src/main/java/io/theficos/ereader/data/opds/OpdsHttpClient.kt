package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpdsHttpClient(credentialStore: CalibreCredentialStore) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Account-scheme-aware auth: emits `Basic ...` for calibre-web
        // credentials and `Bearer ...` for `quire_server` / Cloud accounts.
        // The interceptor origin-guards against attaching credentials to
        // requests that target hosts other than the configured baseUrl.
        // A 401 to a same-origin Bearer request raises the store's re-auth
        // signal (NativeAuth tokens can't be refreshed — the user must sign
        // in again); the interceptor never retries or mutates credentials.
        .addInterceptor(
            AccountAuthInterceptor(
                accountProvider = { credentialStore.getAccount() },
                onBearerUnauthorized = { credentialStore.notifyUnauthorized() },
            )
        )
        .build()
}
