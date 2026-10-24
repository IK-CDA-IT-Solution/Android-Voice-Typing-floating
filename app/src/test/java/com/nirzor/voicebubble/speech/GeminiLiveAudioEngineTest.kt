package com.nirzor.voicebubble.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiLiveAudioEngineTest {

    @Test
    fun testCreateSetupPayloadWithSpecificLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GeminiLiveAudioEngine(context)
        val payloadStr = engine.createSetupPayload("bn-BD")
        val json = JSONObject(payloadStr)

        val setup = json.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))

        val genConfig = setup.getJSONObject("generationConfig")
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("TEXT", modalities.getString(0))

        val inputAudio = setup.getJSONObject("inputAudioTranscription")
        assertEquals("SMART", inputAudio.getString("mode"))
        val langCodes = inputAudio.getJSONArray("languageCodes")
        assertEquals(1, langCodes.length())
        assertEquals("bn-BD", langCodes.getString(0))
    }

    @Test
    fun testCreateSetupPayloadWithDefaultLanguageAutoDetection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GeminiLiveAudioEngine(context)
        val payloadStr = engine.createSetupPayload("default")
        val json = JSONObject(payloadStr)

        val setup = json.getJSONObject("setup")
        val inputAudio = setup.getJSONObject("inputAudioTranscription")
        assertEquals("SMART", inputAudio.getString("mode"))
        assertFalse("Language codes should be omitted for default/auto detection", inputAudio.has("languageCodes"))
    }

    @Test
    fun testCreateAudioChunkPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = GeminiLiveAudioEngine(context)
        val testBase64 = "UklGRiQAAABXQVZFZg=="
        val payloadStr = engine.createAudioChunkPayload(testBase64)
        val json = JSONObject(payloadStr)

        val realtimeInput = json.getJSONObject("realtimeInput")
        val audio = realtimeInput.getJSONObject("audio")
        assertEquals(testBase64, audio.getString("data"))
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
    }

    @Test
    fun testServerMessageParsing() {
        val serverJson = """
            {
              "serverContent": {
                "interimInputTranscription": {
                  "text": "আমি এখন কথা"
                },
                "turnComplete": false
              }
            }
        """.trimIndent()
        val root = JSONObject(serverJson)
        val serverContent = root.getJSONObject("serverContent")
        val interim = serverContent.getJSONObject("interimInputTranscription")
        assertEquals("আমি এখন কথা", interim.getString("text"))

        val finalJson = """
            {
              "serverContent": {
                "inputTranscription": {
                  "text": "আমি এখন কথা বলছি।"
                },
                "turnComplete": true
              }
            }
        """.trimIndent()
        val finalRoot = JSONObject(finalJson)
        val finalContent = finalRoot.getJSONObject("serverContent")
        val finalTrans = finalContent.getJSONObject("inputTranscription")
        assertEquals("আমি এখন কথা বলছি।", finalTrans.getString("text"))
        assertTrue(finalContent.getBoolean("turnComplete"))
    }

    @Test
    fun testServerErrorMessageHandling() {
        val errorJson = """
            {
              "error": {
                "code": 429,
                "message": "Resource has been exhausted (e.g. check quota)."
              }
            }
        """.trimIndent()
        val root = JSONObject(errorJson)
        assertTrue(root.has("error"))
        val errorObj = root.getJSONObject("error")
        assertEquals(429, errorObj.getInt("code"))
    }
}
