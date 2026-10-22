package com.nirzor.voicebubble.service

import com.nirzor.voicebubble.speech.BubbleState

/** Keep terminal errors with user text visible and retryable; only empty errors may time out. */
internal fun shouldAutoDismissBubble(
    keepAlwaysOn: Boolean,
    state: BubbleState,
    retainedPartialDraft: String = ""
): Boolean {
    if (keepAlwaysOn) return false
    return when (state) {
        BubbleState.Idle -> true
        is BubbleState.Error -> state.partialText.isBlank() && retainedPartialDraft.isBlank()
        is BubbleState.Listening, BubbleState.Processing, is BubbleState.Done -> false
    }
}
