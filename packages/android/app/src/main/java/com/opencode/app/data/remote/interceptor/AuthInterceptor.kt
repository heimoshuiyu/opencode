package com.opencode.app.data.remote.interceptor

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val username: String,
    private val password: String,
) : Interceptor {
    private val credential = Credentials.basic(username, password)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", credential)
            .build()
        return chain.proceed(request)
    }
}
