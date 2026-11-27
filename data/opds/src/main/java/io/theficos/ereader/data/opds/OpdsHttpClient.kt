package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.AccountCredentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpdsHttpClient(private val credentialStore: CalibreCredentialStore) {
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

    /** Binds queued private data to the login it belongs to, even across an account change. */
    fun quireHttpFor(account: AccountCredentials): OkHttpClient = transport.newBuilder()
        .quireAuth({ credentialStore.getAccount()?.takeIf { it == account } }, credentialStore::notifyUnauthorized)
        .build()
}
