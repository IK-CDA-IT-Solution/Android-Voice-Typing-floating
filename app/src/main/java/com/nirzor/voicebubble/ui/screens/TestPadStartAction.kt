package com.nirzor.voicebubble.ui.screens

import com.nirzor.voicebubble.speech.BubbleState

/** Returns true only when this tap actually transitioned the test pad into a fresh Listening session. */
internal fun startTestPadSessionIfAccepted(
    currentState: BubbleState,
    startSession: () -> BubbleState
): Boolean {
    if (currentState is BubbleState.Listening || currentState is BubbleState.Processing) {
        return false
    }
    return startSession() is BubbleState.Listening
}
