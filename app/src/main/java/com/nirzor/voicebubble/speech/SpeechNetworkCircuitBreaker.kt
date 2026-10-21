package com.nirzor.voicebubble.speech

/**
 * Limits repeated attempts against Android's online speech provider.
 *
 * This is deliberately separate from Gemini text polishing: SpeechRecognizer is
 * an OS/provider API and may fail even when this app has working internet access.
 */
internal class SpeechNetworkCircuitBreaker(
    private val failureThreshold: Int = 2,
    private val cooldownMillis: Long = 30_000L
) {
    private var consecutiveNetworkFailures = 0
    private var openUntilElapsedMs: Long? = null

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(cooldownMillis > 0L) { "cooldownMillis must be positive" }
    }

    @Synchronized
    fun isOpen(nowElapsedMs: Long): Boolean {
        val openUntil = openUntilElapsedMs ?: return false
        if (nowElapsedMs < openUntil) return true

        // Cooldown ended: allow one half-open online attempt. A failure on that
        // attempt opens the circuit again; a successful online result resets it.
        openUntilElapsedMs = null
        consecutiveNetworkFailures = failureThreshold - 1
        return false
    }

    @Synchronized
    fun recordNetworkFailure(nowElapsedMs: Long): Boolean {
        isOpen(nowElapsedMs)
        consecutiveNetworkFailures += 1
        if (consecutiveNetworkFailures >= failureThreshold) {
            openUntilElapsedMs = nowElapsedMs + cooldownMillis
            return true
        }
        return false
    }

    @Synchronized
    fun recordOnlineSuccess() {
        consecutiveNetworkFailures = 0
        openUntilElapsedMs = null
    }
}
