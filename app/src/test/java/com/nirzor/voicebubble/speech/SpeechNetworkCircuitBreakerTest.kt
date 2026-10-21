package com.nirzor.voicebubble.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechNetworkCircuitBreakerTest {

    @Test
    fun circuitOpensAfterThresholdAndStaysOpenDuringCooldown() {
        val breaker = SpeechNetworkCircuitBreaker(failureThreshold = 2, cooldownMillis = 30_000L)

        assertFalse(breaker.isOpen(1_000L))
        assertFalse(breaker.recordNetworkFailure(1_000L))
        assertFalse(breaker.isOpen(1_500L))

        assertTrue(breaker.recordNetworkFailure(2_000L))
        assertTrue(breaker.isOpen(2_001L))
        assertTrue(breaker.isOpen(31_999L))
        assertFalse(breaker.isOpen(32_000L))
    }

    @Test
    fun failedHalfOpenAttemptReopensCircuit() {
        val breaker = SpeechNetworkCircuitBreaker(failureThreshold = 2, cooldownMillis = 10_000L)

        breaker.recordNetworkFailure(0L)
        breaker.recordNetworkFailure(1L)
        assertFalse(breaker.isOpen(10_001L))

        // One failed attempt after the cooldown should reopen the circuit.
        assertTrue(breaker.recordNetworkFailure(10_002L))
        assertTrue(breaker.isOpen(10_003L))
    }

    @Test
    fun successfulOnlineResultResetsFailureHistory() {
        val breaker = SpeechNetworkCircuitBreaker(failureThreshold = 2, cooldownMillis = 10_000L)

        breaker.recordNetworkFailure(0L)
        breaker.recordOnlineSuccess()

        assertFalse(breaker.recordNetworkFailure(2L))
        assertFalse(breaker.isOpen(3L))
    }
}
