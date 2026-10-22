package com.nirzor.voicebubble.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPartialTranscriptTest {

    @Test
    fun onlyListeningAndProcessingBlockUpdaterDownloads() {
        val stateMachine = BubbleStateMachine()
        assertEquals(false, stateMachine.isSpeechSessionActive)
        stateMachine.startListening()
        assertEquals(true, stateMachine.isSpeechSessionActive)
        stateMachine.moveToProcessing()
        assertEquals(true, stateMachine.isSpeechSessionActive)
        stateMachine.moveToError("provider error", 4)
        assertEquals(false, stateMachine.isSpeechSessionActive)
        stateMachine.resetToIdle()
        stateMachine.startListening()
        stateMachine.moveToDone("result", null)
        assertEquals(false, stateMachine.isSpeechSessionActive)
    }

    @Test
    fun partialTranscriptSurvivesProcessingAndTerminalRecognizerFailure() {
        val stateMachine = BubbleStateMachine()
        val partial = "আমার সোনার বাংলা"

        assertTrue(stateMachine.startListening())
        stateMachine.updatePartialText(partial)
        assertTrue(stateMachine.moveToProcessing())
        stateMachine.moveToError("provider error", 4)

        val error = stateMachine.state.value as BubbleState.Error
        assertEquals("provider error", error.message)
        assertEquals(4, error.errorCode)
        assertEquals(partial, error.partialText)
    }

    @Test
    fun automaticRetryKeepsPartialButFreshSessionClearsIt() {
        val stateMachine = BubbleStateMachine()
        val partial = "পরীক্ষামূলক আংশিক লেখা"

        assertTrue(stateMachine.startListening())
        stateMachine.updatePartialText(partial)
        assertTrue(stateMachine.moveToProcessing())
        assertTrue(stateMachine.restartListening())
        assertEquals(partial, (stateMachine.state.value as BubbleState.Listening).partialText)

        stateMachine.moveToError("provider error", 4)
        stateMachine.resetToIdle()
        assertTrue(stateMachine.startListening())

        assertEquals("", (stateMachine.state.value as BubbleState.Listening).partialText)
    }
}
