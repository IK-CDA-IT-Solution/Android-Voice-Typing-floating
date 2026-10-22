package com.nirzor.voicebubble.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class KeyTestStateTest {
    @Test
    fun onlyExplicitStructuredInvalidKeyReasonsTreatHttp400AsAuthFailure() = runTest {
        suspend fun classify(payload: String): KeyTestState = executeKeyTest {
            throw HttpException(Response.error<Unit>(400, payload.toResponseBody()))
        }
        val invalidKey = """{"error":{"message":"secret-key-must-not-be-shown","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}"""
        assertEquals(KeyTestState.AUTH_ERROR, classify(invalidKey))
        assertEquals(KeyTestState.AUTH_ERROR, classify(invalidKey.replace("API_KEY_INVALID", "API_KEY_EXPIRED")))
        assertEquals(KeyTestState.REQUEST_ERROR, classify(invalidKey.replace("API_KEY_INVALID", "OTHER_REASON")))
        assertEquals(KeyTestState.REQUEST_ERROR, classify("API_KEY_INVALID secret raw text"))
        assertEquals(KeyTestState.REQUEST_ERROR, classify(" ".repeat(8_193) + invalidKey))
    }

    @Test
    fun httpErrorsKeepDistinctSafeOutcomes() = runTest {
        val cases = mapOf(
            400 to KeyTestState.REQUEST_ERROR,
            401 to KeyTestState.AUTH_ERROR,
            403 to KeyTestState.PERMISSION_ERROR,
            404 to KeyTestState.MODEL_ERROR,
            408 to KeyTestState.REQUEST_ERROR,
            429 to KeyTestState.QUOTA_ERROR,
            500 to KeyTestState.SERVER_ERROR,
            503 to KeyTestState.SERVER_ERROR
        )
        for ((code, expected) in cases) {
            val loggedCodes = mutableListOf<Int>()
            val body = "sensitive-key-and-provider-body".toResponseBody()
            val result = executeKeyTest(onHttpStatus = { loggedCodes.add(it) }) {
                throw HttpException(Response.error<Unit>(code, body))
            }
            assertEquals(expected, result)
            assertEquals(listOf(code), loggedCodes)
            assertFalse(result.toString().contains("sensitive"))
            assertFalse(result == KeyTestState.INVALID)
        }
    }

    @Test
    fun successNetworkTimeoutAndUnexpectedFailuresAreDistinct() = runTest {
        assertEquals(KeyTestState.VALID, executeKeyTest {})
        assertEquals(KeyTestState.NETWORK_ERROR, executeKeyTest { throw IOException("secret") })
        assertEquals(KeyTestState.TIMEOUT, executeKeyTest { throw SocketTimeoutException("secret") })
        assertEquals(KeyTestState.UNKNOWN_ERROR, executeKeyTest { throw IllegalStateException("secret") })
        assertEquals(KeyTestState.TIMEOUT, executeKeyTest(timeoutMillis = 10) { delay(11) })
    }

    @Test
    fun callerCancellationIsNotReportedAsInvalidOrNetworkFailure() = runTest {
        var propagated = false
        try {
            executeKeyTest { throw CancellationException("cancelled") }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
