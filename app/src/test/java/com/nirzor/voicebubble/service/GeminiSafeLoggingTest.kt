package com.nirzor.voicebubble.service

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiSafeLoggingTest {
    @Before
    fun clearAndroidLogSink() {
        ShadowLog.clear()
    }

    @After
    fun clearAndroidLogSinkAfterTest() {
        ShadowLog.clear()
    }

    @Test
    fun productionPolishFallbackNeverLogsRejectedHeaderCanaryInEitherBuildVariant() = runBlocking {
        val fakeKey = "fakecanary\nfakecanary"
        val rejected = runCatching {
            Request.Builder().url("https://example.invalid/").header("x-goog-api-key", fakeKey).build()
        }.exceptionOrNull()
        assertTrue("The fake canary must reproduce the raw exception leak risk", rejected?.message?.contains("fakecanary") == true)

        val originalDraft = "keep this partial draft"
        assertEquals(originalDraft, GeminiApiClient.polishWithApiKey(originalDraft, fakeKey))
        val entries = geminiAndroidLogs()
        assertTrue(entries.any { it.msg == "Gemini failure category=unexpected" })
        assertLogsContainNoCanaryOrThrowable(entries)
    }

    @Test
    fun realOkHttpHeaderRejectionDoesNotLeakCanaryToAndroidLogs() = runBlocking {
        // OkHttp 4.10 rejects this invalid header before any network request is made.
        val result = GeminiApiClient.testApiKey("fakecanary\nfakecanary")

        assertEquals(KeyTestState.UNKNOWN_ERROR, result)
        val entries = geminiAndroidLogs()
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.msg.contains("category=unexpected") })
        assertLogsContainNoCanaryOrThrowable(entries)
    }

    @Test
    fun maliciousHttpErrorBodyHeadersCauseAndSuppressedErrorsNeverReachLogSink() {
        val request = Request.Builder().url("https://example.invalid/").build()
        val rawResponse = OkHttpResponse.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(418)
            .message("fakecanary")
            .header("X-Fake-Canary", "fakecanary")
            .body("fakecanary response body".toResponseBody())
            .build()
        val exception = HttpException(Response.error<Unit>("fakecanary response body".toResponseBody(), rawResponse))
        exception.initCause(IllegalStateException("fakecanary cause"))
        exception.addSuppressed(IllegalArgumentException("fakecanary suppressed"))

        GeminiSafeLogger.logFailure(exception)

        val entries = geminiAndroidLogs()
        assertEquals(1, entries.size)
        assertTrue(entries.single().msg.contains("category=http code=418"))
        assertLogsContainNoCanaryOrThrowable(entries)
    }

    @Test
    fun onlyValidNumericHttpCodesAreWritten() {
        GeminiSafeLogger.logHttpStatus(9999)

        val entries = geminiAndroidLogs()
        assertEquals(1, entries.size)
        assertTrue(entries.single().msg.contains("category=http"))
        assertFalse(entries.single().msg.contains("9999"))
        assertLogsContainNoCanaryOrThrowable(entries)
    }

    private fun geminiAndroidLogs(): List<ShadowLog.LogItem> =
        ShadowLog.getLogs().filter { it.tag == "GeminiApiClient" }

    private fun assertLogsContainNoCanaryOrThrowable(entries: List<ShadowLog.LogItem>) {
        val capturedSinkContent = ShadowLog.getLogs().joinToString("\n") { item ->
            item.msg + "\n" + (item.throwable?.stackTraceToString() ?: "")
        }
        assertFalse(capturedSinkContent.contains("fakecanary", ignoreCase = true))
        assertTrue("Throwable was sent to Android Log", entries.none { it.throwable != null })
    }
}
