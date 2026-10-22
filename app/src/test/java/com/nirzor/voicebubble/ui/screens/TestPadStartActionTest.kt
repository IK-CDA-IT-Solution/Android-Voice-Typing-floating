package com.nirzor.voicebubble.ui.screens

import com.nirzor.voicebubble.speech.BubbleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPadStartActionTest {
    @Test
    fun processingTapPreservesPartialDraftAndDoesNotStartAnotherRecognizer() {
        val partialDraft = "draft captured before processing"
        var transcript = partialDraft
        var recognizerStarts = 0

        val started = startTestPadSessionIfAccepted(BubbleState.Processing) {
            recognizerStarts += 1
            BubbleState.Listening()
        }
        if (started) transcript = ""

        assertFalse(started)
        assertEquals(partialDraft, transcript)
        assertEquals(0, recognizerStarts)
    }

    @Test
    fun rejectedStartPreservesDraftButAcceptedListeningStartClearsIt() {
        var transcript = "keep me if provider rejects"
        val rejected = startTestPadSessionIfAccepted(BubbleState.Idle) { BubbleState.Idle }
        if (rejected) transcript = ""
        assertFalse(rejected)
        assertEquals("keep me if provider rejects", transcript)

        val accepted = startTestPadSessionIfAccepted(BubbleState.Idle) { BubbleState.Listening() }
        if (accepted) transcript = ""
        assertTrue(accepted)
        assertEquals("", transcript)
    }
}
