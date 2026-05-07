package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpdsHttpClient(credentialStore: CalibreCredentialStore) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(BasicAuthInterceptor { credentialStore.get() })
        .build()
}
