package com.nirzor.voicebubble.service

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

enum class KeyTestState {
    IDLE, TESTING, VALID, INVALID, AUTH_ERROR, PERMISSION_ERROR, MODEL_ERROR,
    QUOTA_ERROR, SERVER_ERROR, REQUEST_ERROR, NETWORK_ERROR, TIMEOUT, UNKNOWN_ERROR
}

internal fun keyTestStateForHttpCode(code: Int): KeyTestState = when (code) {
    401 -> KeyTestState.AUTH_ERROR
    403 -> KeyTestState.PERMISSION_ERROR
    404 -> KeyTestState.MODEL_ERROR
    429 -> KeyTestState.QUOTA_ERROR
    in 500..599 -> KeyTestState.SERVER_ERROR
    in 400..499 -> KeyTestState.REQUEST_ERROR
    else -> KeyTestState.UNKNOWN_ERROR
}

/** Only inspect bounded structured ErrorInfo fields, never provider message text. */
internal fun keyTestStateForHttpError(error: HttpException): KeyTestState {
    val fallback = keyTestStateForHttpCode(error.code())
    if (error.code() != 400) return fallback
    val isAuthenticationFailure = runCatching {
        error.response()?.errorBody()?.use { body ->
            val source = body.source()
            source.request(8_193L)
            if (source.buffer.size > 8_192L) return@use false
            val payload = Moshi.Builder().build().adapter(Map::class.java)
                .fromJson(source.buffer.clone())
            val providerError = payload?.get("error") as? Map<*, *> ?: return@use false
            if (providerError["status"] == "UNAUTHENTICATED") return@use true
            val details = providerError["details"] as? List<*> ?: return@use false
            details.filterIsInstance<Map<*, *>>().any {
                it["@type"] == "type.googleapis.com/google.rpc.ErrorInfo" &&
                    it["reason"] in setOf("API_KEY_INVALID", "API_KEY_EXPIRED")
            }
        } == true
    }.getOrDefault(false)
    return if (isAuthenticationFailure) KeyTestState.AUTH_ERROR else fallback
}

/** Never expose exception messages or response bodies: they can contain credentials. */
internal suspend fun executeKeyTest(
    timeoutMillis: Long = 35_000L,
    onHttpStatus: (Int) -> Unit = {},
    request: suspend () -> Unit
): KeyTestState = try {
    withTimeout(timeoutMillis) { request() }
    KeyTestState.VALID
} catch (_: TimeoutCancellationException) {
    KeyTestState.TIMEOUT
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    onHttpStatus(e.code())
    keyTestStateForHttpError(e)
} catch (_: SocketTimeoutException) {
    KeyTestState.TIMEOUT
} catch (_: IOException) {
    KeyTestState.NETWORK_ERROR
} catch (_: Exception) {
    KeyTestState.UNKNOWN_ERROR
}
