package com.nirzor.voicebubble.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.nirzor.voicebubble.service.GeminiSafeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveAudioEngine(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "GeminiLiveAudio"
        private const val LIVE_MODEL = "models/gemini-3.5-transcribe-live"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WS_BASE_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isRecording = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    private var activeWebSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var stopWatchdogJob: Job? = null

    private val accumulatedFinalText = StringBuilder()
    private var activeLanguageCode: String = "bn-BD"
    private var sessionStartTimeMs: Long = 0L

    private var onRmsChangedCallback: ((Float) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String, String, Long) -> Unit)? = null
    private var onErrorCallback: ((String, Int) -> Unit)? = null

    fun isRecordingActive(): Boolean = isRecording.get() || isConnecting.get()

    fun startListening(
        languageCode: String = "bn-BD",
        apiKey: String,
        onRmsChanged: ((Float) -> Unit)? = null,
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)? = null,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)? = null
    ): Boolean {
        if (apiKey.isBlank()) {
            onErrorOccurred?.invoke("Gemini API key is missing", -1)
            return false
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onErrorOccurred?.invoke("Audio recording permission missing", -2)
            return false
        }

        cancelSession()

        activeLanguageCode = languageCode
        sessionStartTimeMs = System.currentTimeMillis()
        accumulatedFinalText.clear()
        isConnecting.set(true)
        isStopping.set(false)

        onRmsChangedCallback = onRmsChanged
        onPartialResultCallback = onPartialResult
        onFinalResultCallback = onFinalResult
        onErrorCallback = onErrorOccurred

        val wsUrl = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket connected")
                isConnecting.set(false)
                activeWebSocket = webSocket

                val setupPayload = createSetupPayload(activeLanguageCode)
                webSocket.send(setupPayload)

                startAudioRecordLoop(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closing: code=$code")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closed: code=$code")
                if (isStopping.get()) {
                    deliverFinalResult()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val statusCode = response?.code ?: -1
                GeminiSafeLogger.logFailure(t)
                if (statusCode != -1) {
                    GeminiSafeLogger.logHttpStatus(statusCode)
                }
                cleanup()
                mainHandler.post {
                    onErrorCallback?.invoke("Gemini Live connection failed", statusCode)
                }
            }
        }

        return try {
            client.newWebSocket(request, listener)
            true
        } catch (e: Exception) {
            GeminiSafeLogger.logFailure(e)
            cleanup()
            false
        }
    }

    private fun startAudioRecordLoop(webSocket: WebSocket) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuf, 4096)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord state not initialized")
                record.release()
                mainHandler.post {
                    onErrorCallback?.invoke("AudioRecord initialization failed", -3)
                }
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                val audioBuffer = ByteArray(2048)
                while (isActive && isRecording.get()) {
                    val read = record.read(audioBuffer, 0, audioBuffer.size)
                    if (read > 0) {
                        var sumSquares = 0.0
                        val sampleCount = read / 2
                        for (i in 0 until read step 2) {
                            val sample = ((audioBuffer[i + 1].toInt() shl 8) or (audioBuffer[i].toInt() and 0xFF)).toShort()
                            sumSquares += sample * sample
                        }
                        if (sampleCount > 0) {
                            val rms = kotlin.math.sqrt(sumSquares / sampleCount)
                            val rmsdB = if (rms > 0) (20.0 * kotlin.math.log10(rms / 32767.0)).toFloat().coerceIn(-100f, 10f) else -100f
                            mainHandler.post { onRmsChangedCallback?.invoke(rmsdB) }
                        }

                        val b64 = Base64.encodeToString(audioBuffer, 0, read, Base64.NO_WRAP)
                        val chunkJson = createAudioChunkPayload(b64)
                        webSocket.send(chunkJson)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord startup failed", e)
            mainHandler.post {
                onErrorCallback?.invoke("AudioRecord startup failed", -3)
            }
        }
    }

    private fun handleServerMessage(text: String) {
        try {
            val root = JSONObject(text)
            val errorObj = root.optJSONObject("error")
            if (errorObj != null) {
                val code = errorObj.optInt("code", 400)
                GeminiSafeLogger.logHttpStatus(code)
                mainHandler.post {
                    onErrorCallback?.invoke("Gemini Live server error: code=$code", code)
                }
                cleanup()
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // 1. Check interimInputTranscription (real-time stream while speaking)
            val interim = serverContent.optJSONObject("interimInputTranscription")
            if (interim != null) {
                val interimText = interim.optString("text", "")
                if (interimText.isNotBlank()) {
                    val combined = if (accumulatedFinalText.isNotEmpty()) {
                        "${accumulatedFinalText.toString().trim()} $interimText"
                    } else {
                        interimText
                    }
                    mainHandler.post {
                        onPartialResultCallback?.invoke(combined)
                    }
                }
            }

            // 2. Check finalized inputTranscription (smart formatted segment)
            val finalTrans = serverContent.optJSONObject("inputTranscription")
            if (finalTrans != null) {
                val finalText = finalTrans.optString("text", "")
                if (finalText.isNotBlank()) {
                    synchronized(accumulatedFinalText) {
                        if (accumulatedFinalText.isNotEmpty() && !accumulatedFinalText.endsWith(" ")) {
                            accumulatedFinalText.append(" ")
                        }
                        accumulatedFinalText.append(finalText.trim())
                    }
                    val currentText = accumulatedFinalText.toString().trim()
                    mainHandler.post {
                        onPartialResultCallback?.invoke(currentText)
                    }
                }
            }

            // 3. Check modelTurn text parts
            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val partText = part?.optString("text", "") ?: ""
                        if (partText.isNotBlank()) {
                            synchronized(accumulatedFinalText) {
                                if (accumulatedFinalText.isNotEmpty() && !accumulatedFinalText.endsWith(" ")) {
                                    accumulatedFinalText.append(" ")
                                }
                                accumulatedFinalText.append(partText.trim())
                            }
                            mainHandler.post {
                                onPartialResultCallback?.invoke(accumulatedFinalText.toString().trim())
                            }
                        }
                    }
                }
            }

            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (turnComplete && isStopping.get()) {
                deliverFinalResult()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing server message", e)
        }
    }

    fun stopListening() {
        if (!isRecording.get() && !isConnecting.get()) return
        isStopping.set(true)
        isRecording.set(false)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordingJob?.cancel()
        recordingJob = null

        activeWebSocket?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}")

        stopWatchdogJob?.cancel()
        stopWatchdogJob = scope.launch {
            delay(2500L)
            deliverFinalResult()
        }
    }

    private fun deliverFinalResult() {
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null

        val resultText = synchronized(accumulatedFinalText) {
            accumulatedFinalText.toString().trim()
        }
        val durationMs = if (sessionStartTimeMs > 0L) System.currentTimeMillis() - sessionStartTimeMs else 0L

        cleanup()

        mainHandler.post {
            onFinalResultCallback?.invoke(resultText, activeLanguageCode, durationMs)
        }
    }

    fun cancelSession() {
        isConnecting.set(false)
        isRecording.set(false)
        isStopping.set(false)
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            activeWebSocket?.cancel()
        } catch (_: Exception) {}
        activeWebSocket = null

        accumulatedFinalText.clear()
    }

    private fun cleanup() {
        isConnecting.set(false)
        isRecording.set(false)
        isStopping.set(false)
        stopWatchdogJob?.cancel()
        stopWatchdogJob = null
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            activeWebSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        activeWebSocket = null
    }

    fun destroy() {
        cancelSession()
        scope.cancel()
    }

    internal fun createSetupPayload(languageCode: String): String {
        val root = JSONObject()
        val setup = JSONObject()
        setup.put("model", LIVE_MODEL)

        val genConfig = JSONObject()
        genConfig.put("responseModalities", JSONArray().apply { put("TEXT") })
        setup.put("generationConfig", genConfig)

        val inputAudio = JSONObject()
        inputAudio.put("mode", "SMART")
        if (languageCode.isNotBlank() && languageCode != "default") {
            inputAudio.put("languageCodes", JSONArray().apply { put(languageCode) })
        }
        setup.put("inputAudioTranscription", inputAudio)

        root.put("setup", setup)
        return root.toString()
    }

    internal fun createAudioChunkPayload(base64Pcm: String): String {
        val root = JSONObject()
        val realtimeInput = JSONObject()
        val audio = JSONObject()
        audio.put("data", base64Pcm)
        audio.put("mimeType", "audio/pcm;rate=16000")
        realtimeInput.put("audio", audio)
        root.put("realtimeInput", realtimeInput)
        return root.toString()
    }
}
