package com.neilluo.scrolllock.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Talks to the relay. Both calls carry only the read token, never anything from Instagram. */
interface RelayClient {
    suspend fun status(s: Settings): Result<RelayStatus>
    suspend fun register(s: Settings, fcmToken: String): Result<Unit>
}

class RelayApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) : RelayClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun status(s: Settings): Result<RelayStatus> = runCatching {
        val url = (s.relayUrl.trimEnd('/') + "/api/status").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("mode", s.mode.wire)
            ?.addQueryParameter("minutes", s.myTurnMinutes.toString())
            ?.addQueryParameter("grace", s.graceSeconds.toString())
            ?.build() ?: error("Relay URL is not valid")
        val req = Request.Builder().url(url).header("authorization", "Bearer ${s.readToken}").get().build()
        val body = execute(req)
        json.decodeFromString(RelayStatus.serializer(), body)
    }

    override suspend fun register(s: Settings, fcmToken: String): Result<Unit> = runCatching {
        val url = (s.relayUrl.trimEnd('/') + "/api/register-device").toHttpUrlOrNull() ?: error("Relay URL is not valid")
        val payload = buildJsonObject {
            put("token", fcmToken)
            put("mode", s.mode.wire)
            put("minutes", s.myTurnMinutes)
            put("grace", s.graceSeconds)
        }.toString()
        val req = Request.Builder().url(url)
            .header("authorization", "Bearer ${s.readToken}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        execute(req)
        Unit
    }

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body.string()
                        if (it.isSuccessful) cont.resume(text)
                        else cont.resumeWithException(IOException("Relay answered ${it.code}"))
                    }
                }
            })
        }
    }
}
