package com.opencode.app.data.remote.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder

class DirectoryInterceptor(
    private val directoryProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val directory = directoryProvider() ?: return chain.proceed(chain.request())
        val encoded = URLEncoder.encode(directory, "UTF-8")
        val request = chain.request().newBuilder()
            .header("x-opencode-directory", encoded)
            .build()
        return chain.proceed(request)
    }
}
