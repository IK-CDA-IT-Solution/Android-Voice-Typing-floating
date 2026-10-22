package com.nirzor.voicebubble.service

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Emits only fixed failure categories and validated HTTP status codes; never serializes errors. */
internal object GeminiSafeLogger {
    private const val TAG = "GeminiApiClient"

    private enum class FailureCategory(val label: String) {
        TIMEOUT("timeout"),
        NETWORK("network"),
        HTTP("http"),
        UNEXPECTED("unexpected")
    }

    fun logFailure(error: Throwable) {
        when (error) {
            is HttpException -> logHttpStatus(error.code())
            is TimeoutCancellationException, is SocketTimeoutException -> logCategory(FailureCategory.TIMEOUT)
            is IOException -> logCategory(FailureCategory.NETWORK)
            else -> logCategory(FailureCategory.UNEXPECTED)
        }
    }

    fun logHttpStatus(code: Int) {
        if (code in 100..599) {
            Log.w(TAG, "Gemini failure category=${FailureCategory.HTTP.label} code=$code")
        } else {
            logCategory(FailureCategory.HTTP)
        }
    }

    fun logKeyTestResult(state: KeyTestState) {
        when (state) {
            KeyTestState.TIMEOUT -> logCategory(FailureCategory.TIMEOUT)
            KeyTestState.NETWORK_ERROR -> logCategory(FailureCategory.NETWORK)
            KeyTestState.UNKNOWN_ERROR -> logCategory(FailureCategory.UNEXPECTED)
            else -> Unit
        }
    }

    private fun logCategory(category: FailureCategory) {
        Log.w(TAG, "Gemini failure category=${category.label}")
    }
}
