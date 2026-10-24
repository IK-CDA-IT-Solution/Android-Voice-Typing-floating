package com.nirzor.voicebubble.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.nirzor.voicebubble.R
import com.nirzor.voicebubble.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import java.util.Locale

class SpeechEngine(private val context: Context) {

    companion object {
        internal val sessionUpdateLock = Any()
        private val stateMachines = java.util.WeakHashMap<BubbleStateMachine, Unit>()
        val isAnySessionActive: Boolean
            get() = synchronized(sessionUpdateLock) {
                stateMachines.keys.any { it.isSpeechSessionActive }
            }
    }

    private val TAG = "SpeechEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    val stateMachine = BubbleStateMachine()
    val speechState: StateFlow<BubbleState> = stateMachine.state

    private var speechRecognizer: SpeechRecognizer? = null
    private var startTimeMs: Long = 0L
    private var recognizerStartJob: Job? = null
    private var startRequestId = 0L
    private var isStartPending = false
    private var hasRetriedBusy = false
    private var hasRetriedOnline = false
    private var hasRetriedOnDevice = false
    private val onlineNetworkCircuitBreaker = SpeechNetworkCircuitBreaker(
        failureThreshold = 2,
        cooldownMillis = 30_000L
    )
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var accumulatedText = StringBuilder()
    private var isContinuousSessionActive = false
    private var isManualStopPending = false
    private var sessionStartTimeMs = 0L
    private var activeLanguageCode: String = "bn-BD"
    private var activeOnFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)? = null
    private var activeOnErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)? = null

    private val processingTimeoutRunnable = Runnable {
        if (stateMachine.state.value is BubbleState.Processing) {
            Log.w(TAG, "Speech processing timed out after 10s watchdog")
            invalidatePendingStart()
            cleanupRecognizer()
            val errorMsg = context.getString(R.string.speech_err_processing_timeout)
            stateMachine.moveToError(errorMsg, -3)
            activeOnErrorOccurred?.invoke(errorMsg, -3)
        }
    }

    private fun requestAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setOnAudioFocusChangeListener { /* transient focus change */ }
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus", e)
        }
    }

    init {
        synchronized(sessionUpdateLock) { stateMachines[stateMachine] = Unit }
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed", e)
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context) || isOnDeviceRecognitionAvailable()
    }

    private fun isOnDeviceRecognitionAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    fun startSession(
        languageCode: String = "bn-BD",
        playTone: Boolean = true,
        enableHaptic: Boolean = true,
        continuousMode: Boolean = false,
        onPartialResult: ((String) -> Unit)? = null,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)? = null,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)? = null
    ): Unit = synchronized(sessionUpdateLock) {
        if (UpdateManager.getInstance(context).isBusy.value) {
            onErrorOccurred?.invoke(context.getString(R.string.speech_err_updating), -2)
            return
        }
        // Reserve the state immediately so rapid taps cannot queue parallel recognizers.
        when (stateMachine.state.value) {
            is BubbleState.Error, is BubbleState.Done -> stateMachine.resetToIdle()
            else -> Unit
        }

        if (isStartPending || !stateMachine.canAcceptTap()) {
            Log.d(TAG, "Ignoring tap while a speech session is starting or active")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val errorMsg = context.getString(R.string.speech_err_permissions)
            stateMachine.moveToError(errorMsg, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            onErrorOccurred?.invoke(errorMsg, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        val defaultRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val onDeviceRecognizerAvailable = isOnDeviceRecognitionAvailable()
        if (!defaultRecognizerAvailable && !onDeviceRecognizerAvailable) {
            val errorMsg = context.getString(R.string.speech_not_available)
            stateMachine.moveToError(errorMsg, -1)
            onErrorOccurred?.invoke(errorMsg, -1)
            return
        }

        val circuitOpen = onlineNetworkCircuitBreaker.isOpen(SystemClock.elapsedRealtime())
        if (circuitOpen && !onDeviceRecognizerAvailable) {
            // With no local provider available, fail fast during the cooldown rather than
            // repeatedly submitting requests to a provider that has just failed twice.
            val errorMsg = context.getString(R.string.speech_err_network_cooldown)
            stateMachine.moveToError(errorMsg, SpeechRecognizer.ERROR_NETWORK)
            onErrorOccurred?.invoke(errorMsg, SpeechRecognizer.ERROR_NETWORK)
            return
        }

        if (!stateMachine.startListening(isContinuous = continuousMode)) return

        hasRetriedBusy = false
        hasRetriedOnline = false
        hasRetriedOnDevice = false
        isContinuousSessionActive = continuousMode
        isManualStopPending = false
        accumulatedText.clear()
        sessionStartTimeMs = System.currentTimeMillis()
        activeLanguageCode = languageCode
        activeOnFinalResult = onFinalResult
        activeOnErrorOccurred = onErrorOccurred

        // Invalidate callbacks from the previous recognizer before cancelling it.
        invalidatePendingStart()
        cleanupRecognizer()
        scheduleRecognizerStart(
            delayMillis = 300L,
            languageCode = languageCode,
            playTone = playTone,
            enableHaptic = enableHaptic,
            onPartialResult = onPartialResult,
            onFinalResult = onFinalResult,
            onErrorOccurred = onErrorOccurred,
            isRetry = false,
            forceOnDevice = !defaultRecognizerAvailable || (circuitOpen && onDeviceRecognizerAvailable),
            continuousMode = continuousMode
        )
        if (circuitOpen && onDeviceRecognizerAvailable && defaultRecognizerAvailable) {
            onErrorOccurred?.invoke(
                context.getString(R.string.speech_retrying_offline),
                SpeechRecognizer.ERROR_NETWORK
            )
        }
    }

    private fun scheduleRecognizerStart(
        delayMillis: Long,
        languageCode: String,
        playTone: Boolean,
        enableHaptic: Boolean,
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)?,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)?,
        isRetry: Boolean,
        forceOnDevice: Boolean,
        continuousMode: Boolean = false
    ) {
        recognizerStartJob?.cancel()
        val requestId = ++startRequestId
        isStartPending = true

        recognizerStartJob = scope.launch {
            delay(delayMillis)
            if (requestId != startRequestId) return@launch

            internalStartRecognizer(
                languageCode = languageCode,
                playTone = playTone,
                enableHaptic = enableHaptic,
                onPartialResult = onPartialResult,
                onFinalResult = onFinalResult,
                onErrorOccurred = onErrorOccurred,
                isRetry = isRetry,
                forceOnDevice = forceOnDevice,
                continuousMode = continuousMode,
                requestId = requestId
            )
        }
    }

    private fun invalidatePendingStart() {
        startRequestId += 1
        recognizerStartJob?.cancel()
        recognizerStartJob = null
        isStartPending = false
    }

    private fun internalStartRecognizer(
        languageCode: String,
        playTone: Boolean,
        enableHaptic: Boolean,
        onPartialResult: ((String) -> Unit)?,
        onFinalResult: ((rawText: String, lang: String, durationMs: Long) -> Unit)?,
        onErrorOccurred: ((errorMessage: String, errorCode: Int) -> Unit)?,
        isRetry: Boolean,
        forceOnDevice: Boolean,
        continuousMode: Boolean = false,
        requestId: Long
    ) {
        mainHandler.post {
            // Delayed callbacks from an older attempt must not start or overwrite a newer session.
            if (requestId != startRequestId || !isStartPending) return@post
            isStartPending = false
            recognizerStartJob = null

            if (isRetry) {
                if (!stateMachine.restartListening(isContinuous = continuousMode)) {
                    Log.w(TAG, "Skipping recognizer retry because the session is no longer active")
                    return@post
                }
            } else if (stateMachine.state.value !is BubbleState.Listening) {
                return@post
            }

            if (enableHaptic) triggerHaptic(50)
            if (playTone) playBeep(ToneGenerator.TONE_PROP_BEEP)

            startTimeMs = System.currentTimeMillis()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                if (languageCode != "default") {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                } else {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                }
                if (forceOnDevice) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            try {
                val recognizer = if (forceOnDevice) {
                    if (!isOnDeviceRecognitionAvailable()) {
                        throw IllegalStateException("On-device speech recognition is unavailable")
                    }
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer = recognizer.apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            if (requestId != startRequestId) return
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            stateMachine.updateRms(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            if (requestId != startRequestId) return
                            Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                            stateMachine.moveToProcessing()
                            mainHandler.removeCallbacks(processingTimeoutRunnable)
                            mainHandler.postDelayed(processingTimeoutRunnable, 10_000L)
                        }

                        override fun onError(error: Int) {
                            if (requestId != startRequestId) return
                            mainHandler.removeCallbacks(processingTimeoutRunnable)
                            Log.w(
                                TAG,
                                SpeechDiagnostics.failure(
                                    code = error,
                                    languageCode = languageCode,
                                    onDevice = forceOnDevice,
                                    retry = isRetry
                                )
                            )

                            if (isContinuousSessionActive && !isManualStopPending && error == SpeechRecognizer.ERROR_NO_MATCH) {
                                Log.d(TAG, "SpeechRecognizer NO_MATCH during continuous mode, resuming listening after 300ms")
                                invalidatePendingStart()
                                cleanupRecognizer()
                                scheduleRecognizerStart(
                                    delayMillis = 300L,
                                    languageCode = languageCode,
                                    playTone = false,
                                    enableHaptic = false,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = forceOnDevice,
                                    continuousMode = true
                                )
                                return
                            }

                            if (isContinuousSessionActive && accumulatedText.isNotBlank()) {
                                Log.d(TAG, "SpeechRecognizer error in continuous mode, delivering accumulated transcript")
                                val finalFullText = accumulatedText.toString().trim()
                                val duration = System.currentTimeMillis() - sessionStartTimeMs
                                invalidatePendingStart()
                                cleanupRecognizer()
                                if (enableHaptic) triggerHapticSuccess()
                                onFinalResult?.invoke(finalFullText, languageCode, duration)
                                return
                            }

                            // Retry one busy-service error without allowing overlapping start jobs.
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !hasRetriedBusy) {
                                hasRetriedBusy = true
                                invalidatePendingStart()
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(context.getString(R.string.speech_err_busy), error)
                                scheduleRecognizerStart(
                                    delayMillis = 400L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = forceOnDevice,
                                    continuousMode = isContinuousSessionActive
                                )
                                return
                            }

                            // This is the recognizer/provider's error category, not independent
                            // confirmation that handset connectivity is the root cause.
                            val isNetworkFailure =
                                error == SpeechRecognizer.ERROR_NETWORK ||
                                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                                    error == SpeechRecognizer.ERROR_SERVER

                            // Count only failures from the OS/provider's online speech path.
                            // Two consecutive network/server failures open a short circuit.
                            val circuitOpenNow = if (!forceOnDevice && isNetworkFailure) {
                                onlineNetworkCircuitBreaker.recordNetworkFailure(
                                    SystemClock.elapsedRealtime()
                                )
                            } else {
                                false
                            }

                            // If the circuit is still closed, retry online once for a transient fault.
                            if (!forceOnDevice && isNetworkFailure && !circuitOpenNow && !hasRetriedOnline) {
                                hasRetriedOnline = true
                                Log.w(TAG, "Speech provider returned a network-category error; retrying provider path once")
                                invalidatePendingStart()
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(
                                    context.getString(R.string.speech_retrying_network),
                                    error
                                )
                                scheduleRecognizerStart(
                                    delayMillis = 500L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = false,
                                    continuousMode = isContinuousSessionActive
                                )
                                return
                            }

                            // If the circuit opens or the retry has failed, use a compatible
                            // on-device recognizer instead of continuing to hit the online path.
                            if (
                                !forceOnDevice &&
                                !hasRetriedOnDevice &&
                                isNetworkFailure &&
                                isOnDeviceRecognitionAvailable()
                            ) {
                                hasRetriedOnDevice = true
                                Log.w(TAG, "Speech provider network-category error; trying on-device recognition")
                                invalidatePendingStart()
                                cleanupRecognizer()
                                onErrorOccurred?.invoke(
                                    context.getString(R.string.speech_retrying_offline),
                                    error
                                )
                                scheduleRecognizerStart(
                                    delayMillis = 350L,
                                    languageCode = languageCode,
                                    playTone = playTone,
                                    enableHaptic = enableHaptic,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = true,
                                    continuousMode = isContinuousSessionActive
                                )
                                return
                            }

                            // A non-network result/error indicates that the online provider is
                            // reachable again; clear the failure streak so the circuit can close.
                            if (!forceOnDevice && !isNetworkFailure) {
                                onlineNetworkCircuitBreaker.recordOnlineSuccess()
                            }

                            val errorMsg = if (forceOnDevice && isNetworkFailure) {
                                context.getString(R.string.speech_err_offline_unavailable)
                            } else {
                                getErrorMessage(error)
                            }
                            invalidatePendingStart()
                            cleanupRecognizer()
                            stateMachine.moveToError(errorMsg, error)
                            if (enableHaptic) triggerHapticDouble()
                            onErrorOccurred?.invoke(errorMsg, error)
                        }

                        override fun onResults(results: Bundle?) {
                            if (requestId != startRequestId) return
                            mainHandler.removeCallbacks(processingTimeoutRunnable)
                            if (!forceOnDevice) {
                                onlineNetworkCircuitBreaker.recordOnlineSuccess()
                            }
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""

                            if (text.isNotBlank()) {
                                if (accumulatedText.isNotEmpty()) {
                                    accumulatedText.append(" ")
                                }
                                accumulatedText.append(text)
                            }

                            if (isContinuousSessionActive && !isManualStopPending) {
                                val currentAccumulated = accumulatedText.toString().trim()
                                if (currentAccumulated.isNotBlank()) {
                                    stateMachine.updatePartialText(currentAccumulated)
                                    onPartialResult?.invoke(currentAccumulated)
                                }
                                cleanupRecognizer()
                                scheduleRecognizerStart(
                                    delayMillis = 300L,
                                    languageCode = languageCode,
                                    playTone = false,
                                    enableHaptic = false,
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onErrorOccurred = onErrorOccurred,
                                    isRetry = true,
                                    forceOnDevice = forceOnDevice,
                                    continuousMode = true
                                )
                                return
                            }

                            val finalFullText = if (isContinuousSessionActive) {
                                accumulatedText.toString().trim()
                            } else {
                                text
                            }
                            val duration = System.currentTimeMillis() - if (isContinuousSessionActive) sessionStartTimeMs else startTimeMs

                            if (finalFullText.isNotBlank()) {
                                invalidatePendingStart()
                                cleanupRecognizer()
                                if (enableHaptic) triggerHapticSuccess()
                                if (playTone) playBeep(ToneGenerator.TONE_PROP_ACK)
                                onFinalResult?.invoke(finalFullText, languageCode, duration)
                            } else {
                                val msg = context.getString(R.string.speech_err_no_match)
                                invalidatePendingStart()
                                cleanupRecognizer()
                                stateMachine.moveToError(msg, SpeechRecognizer.ERROR_NO_MATCH)
                                onErrorOccurred?.invoke(msg, SpeechRecognizer.ERROR_NO_MATCH)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (requestId != startRequestId) return
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partial = matches?.firstOrNull()?.trim() ?: ""
                            if (partial.isNotBlank()) {
                                stateMachine.updatePartialText(partial)
                                onPartialResult?.invoke(partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    requestAudioFocus()
                    startListening(intent)
                }
            } catch (e: Exception) {
                if (requestId != startRequestId) return@post
                Log.w(
                    TAG,
                    SpeechDiagnostics.failure(
                        code = -1,
                        languageCode = languageCode,
                        onDevice = forceOnDevice,
                        retry = isRetry
                    )
                )
                invalidatePendingStart()
                cleanupRecognizer()
                val errorMsg = context.getString(R.string.speech_err_unknown, -1)
                stateMachine.moveToError(errorMsg, -1)
                onErrorOccurred?.invoke(errorMsg, -1)
            }
        }
    }

    fun stopSession() {
        mainHandler.post {
            mainHandler.removeCallbacks(processingTimeoutRunnable)
            if (isContinuousSessionActive && accumulatedText.isNotBlank()) {
                val finalFullText = accumulatedText.toString().trim()
                val duration = System.currentTimeMillis() - sessionStartTimeMs
                invalidatePendingStart()
                cleanupRecognizer()
                activeOnFinalResult?.invoke(finalFullText, activeLanguageCode, duration)
                return@post
            }

            if (isStartPending) {
                invalidatePendingStart()
                cleanupRecognizer()
                stateMachine.resetToIdle()
                return@post
            }

            if (isContinuousSessionActive) {
                isManualStopPending = true
            }

            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping speech recognizer", e)
            }
        }
    }

    fun cancelSession() {
        mainHandler.post {
            mainHandler.removeCallbacks(processingTimeoutRunnable)
            isContinuousSessionActive = false
            accumulatedText.clear()
            invalidatePendingStart()
            cleanupRecognizer()
            stateMachine.resetToIdle()
        }
    }

    fun destroy() {
        mainHandler.post {
            mainHandler.removeCallbacks(processingTimeoutRunnable)
            isContinuousSessionActive = false
            accumulatedText.clear()
            invalidatePendingStart()
            cleanupRecognizer()
            stateMachine.resetToIdle()
            synchronized(sessionUpdateLock) { stateMachines.remove(stateMachine) }
            try {
                toneGenerator?.release()
                toneGenerator = null
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator release error", e)
            }
            scope.cancel()
        }
    }

    private fun cleanupRecognizer() {
        mainHandler.removeCallbacks(processingTimeoutRunnable)
        abandonAudioFocus()
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up SpeechRecognizer", e)
        }
    }

    private fun playBeep(toneType: Int) {
        try {
            toneGenerator?.startTone(toneType, 120)
        } catch (e: Exception) {
            Log.w(TAG, "Tone play error", e)
        }
    }

    private fun triggerHaptic(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun triggerHapticSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 40, 60, 80)
                val amplitudes = intArrayOf(0, 180, 0, 255)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 40, 60, 80), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun triggerHapticDouble() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 60, 80, 60)
                val amplitudes = intArrayOf(0, 200, 0, 200)
                vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 60, 80, 60), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic error", e)
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.speech_err_network_timeout)
            SpeechRecognizer.ERROR_NETWORK -> context.getString(R.string.speech_err_network)
            SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.speech_err_audio)
            SpeechRecognizer.ERROR_SERVER -> context.getString(R.string.speech_err_server)
            SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.speech_err_client)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.speech_err_speech_timeout)
            SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.speech_err_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.speech_err_busy_final)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.speech_err_permissions)
            else -> context.getString(R.string.speech_err_unknown, errorCode)
        }
    }
}
