package com.flexy.f1live.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared lenient parser: every upstream here sends far more fields than the app reads. */
internal val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Suspends on OkHttp's async dispatcher and cancels the call when the coroutine is cancelled. */
internal suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    continuation.resumeWithException(
                        IOException("HTTP ${it.code} ${call.request().url}"),
                    )
                    return
                }
                continuation.resume(it.body.string())
            }
        }
    })
}

internal suspend fun OkHttpClient.getText(url: String, userAgent: String): String {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/json")
        .build()
    return newCall(request).awaitBody()
}

internal suspend fun OkHttpClient.getJsonObject(url: String, userAgent: String): JsonObject =
    LenientJson.parseToJsonElement(getText(url, userAgent)) as? JsonObject
        ?: throw IOException("Response is not a JSON object: $url")
