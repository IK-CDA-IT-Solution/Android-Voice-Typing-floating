package com.nirzor.voicebubble.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandProcessorTest {

    @Test
    fun testPunctuationReplacementBangla() {
        val input = "আমি ভালো আছি দাঁড়ি আপনি কেমন আছেন প্রশ্নবোধক"
        val result = VoiceCommandProcessor.process(input)
        assertEquals("আমি ভালো আছি। আপনি কেমন আছেন?", result.processedText)
        assertFalse(result.isCopyCommandTriggered)
        assertFalse(result.isClearAllTriggered)
    }

    @Test
    fun testPunctuationReplacementEnglish() {
        val input = "Hello world comma how are you question mark"
        val result = VoiceCommandProcessor.process(input)
        assertEquals("Hello world, how are you?", result.processedText)
        assertFalse(result.isCopyCommandTriggered)
    }

    @Test
    fun testSubstringsAreNotCorrupted() {
        // Words containing punctuation names as substrings MUST NOT be corrupted
        val inputBangla = "সে সেখানে দাঁড়িয়ে ছিল এবং খরচ কমানো নিয়ে পরিক্রমা করলো"
        val resultBangla = VoiceCommandProcessor.process(inputBangla)
        assertEquals("সে সেখানে দাঁড়িয়ে ছিল এবং খরচ কমানো নিয়ে পরিক্রমা করলো", resultBangla.processedText)

        val inputEnglish = "Please execute this command and recommend it without comma"
        val resultEnglish = VoiceCommandProcessor.process(inputEnglish)
        assertEquals("Please execute this command and recommend it without,", resultEnglish.processedText)
    }

    @Test
    fun testStandaloneCopyCommand() {
        val input = "কপি করো"
        val result = VoiceCommandProcessor.process(input)
        assertTrue(result.isCopyCommandTriggered)
        assertEquals("", result.processedText)
    }

    @Test
    fun testUtteranceEndingWithCopyCommand() {
        val input = "এই মেসেজটি লিখে রাখো কপি করো"
        val result = VoiceCommandProcessor.process(input)
        assertTrue(result.isCopyCommandTriggered)
        assertEquals("এই মেসেজটি লিখে রাখো", result.processedText)
    }

    @Test
    fun testUtteranceEndingWithDeleteWordCommand() {
        val input = "আমি বাজারে যাবো না মুছো"
        val result = VoiceCommandProcessor.process(input)
        assertEquals("আমি বাজারে যাবো", result.processedText)
        assertFalse(result.isCopyCommandTriggered)
    }

    @Test
    fun testStandaloneDeleteWordCommand() {
        val input = "মুছো"
        val result = VoiceCommandProcessor.process(input)
        assertTrue(result.isDeleteLastWordTriggered)
        assertEquals("", result.processedText)
    }

    @Test
    fun testClearAllCommand() {
        val input = "সব মুছো"
        val result = VoiceCommandProcessor.process(input)
        assertTrue(result.isClearAllTriggered)
        assertEquals("", result.processedText)
    }
}
