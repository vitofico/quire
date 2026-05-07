package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentials
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64

class BasicAuthInterceptor(
    private val credentialsProvider: () -> CalibreCredentials?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val creds = credentialsProvider()
        val request = chain.request()
        val withAuth = if (creds != null) {
            val raw = "${creds.username}:${creds.password}"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
            request.newBuilder().header("Authorization", "Basic $encoded").build()
        } else request
        return chain.proceed(withAuth)
    }
}
