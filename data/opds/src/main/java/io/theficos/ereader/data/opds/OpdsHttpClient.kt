package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpdsHttpClient(credentialStore: CalibreCredentialStore) {
    private val transport = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val okHttp: OkHttpClient = transport.newBuilder()
        .catalogAuth(credentialStore::catalogSession)
        .build()

    val quireHttp: OkHttpClient = transport.newBuilder()
        .quireAuth(credentialStore::getAccount, credentialStore::notifyUnauthorized)
        .build()
}
