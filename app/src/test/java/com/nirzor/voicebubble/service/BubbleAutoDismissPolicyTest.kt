package com.nirzor.voicebubble.service

import com.nirzor.voicebubble.speech.BubbleState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleAutoDismissPolicyTest {
    @Test
    fun errorWithPartialDraftRemainsVisibleAndRetryableWithAlwaysOnEitherWay() {
        val errorWithDraft = BubbleState.Error("recognizer failed", 7, "partial words to preserve")

        assertFalse(shouldAutoDismissBubble(keepAlwaysOn = false, state = errorWithDraft))
        assertFalse(shouldAutoDismissBubble(keepAlwaysOn = true, state = errorWithDraft))
        assertFalse(
            shouldAutoDismissBubble(
                keepAlwaysOn = false,
                state = BubbleState.Error("retry failed", 8),
                retainedPartialDraft = "draft retained in preview"
            )
        )
    }

    @Test
    fun emptyTerminalErrorMayDismissOnlyWhenAlwaysOnIsOff() {
        val emptyError = BubbleState.Error("recognizer failed", 7)

        assertTrue(shouldAutoDismissBubble(keepAlwaysOn = false, state = emptyError))
        assertFalse(shouldAutoDismissBubble(keepAlwaysOn = true, state = emptyError))
    }

    @Test
    fun retryOrActiveSpeechStateProtectsBubbleFromAnOldDismissTimer() {
        assertFalse(shouldAutoDismissBubble(false, BubbleState.Listening(partialText = "retry")))
        assertFalse(shouldAutoDismissBubble(false, BubbleState.Processing))
    }
}
