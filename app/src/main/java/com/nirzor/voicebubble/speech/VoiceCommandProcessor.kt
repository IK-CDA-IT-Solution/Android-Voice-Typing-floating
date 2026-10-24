package com.nirzor.voicebubble.speech

import java.text.Normalizer

data class VoiceCommandResult(
    val processedText: String,
    val isCopyCommandTriggered: Boolean = false,
    val isClearAllTriggered: Boolean = false,
    val isDeleteLastWordTriggered: Boolean = false
)

object VoiceCommandProcessor {

    private val punctuations = listOf(
        Normalizer.normalize("দাঁড়ি", Normalizer.Form.NFC) to "।",
        Normalizer.normalize("দাঁড়ি", Normalizer.Form.NFC) to "।",
        Normalizer.normalize("দাড়ি", Normalizer.Form.NFC) to "।",
        Normalizer.normalize("দাড়ি", Normalizer.Form.NFC) to "।",
        Normalizer.normalize("কমা", Normalizer.Form.NFC) to ",",
        Normalizer.normalize("প্রশ্নবোধক", Normalizer.Form.NFC) to "?",
        Normalizer.normalize("প্রশ্নচিহ্ন", Normalizer.Form.NFC) to "?",
        Normalizer.normalize("নতুন প্যারা", Normalizer.Form.NFC) to "\n\n",
        Normalizer.normalize("নতুন লাইন", Normalizer.Form.NFC) to "\n",
        Normalizer.normalize("ফুল স্টপ", Normalizer.Form.NFC) to "।",
        "full stop" to ".",
        "period" to ".",
        "comma" to ",",
        "question mark" to "?",
        "new paragraph" to "\n\n",
        "new line" to "\n"
    )

    fun process(text: String): VoiceCommandResult {
        if (text.isBlank()) return VoiceCommandResult(text)

        var result = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        val lower = result.lowercase()

        // 1. Standalone action commands
        if (lower == "কপি করো" || lower == "কপি কর" || lower == "copy that" || lower == "copy now") {
            return VoiceCommandResult(processedText = "", isCopyCommandTriggered = true)
        }
        if (lower == "সব মুছো" || lower == "সব মুছে ফেলো" || lower == "clear all") {
            return VoiceCommandResult(processedText = "", isClearAllTriggered = true)
        }
        if (lower == "মুছো" || lower == "মুছে ফেলো" || lower == "delete that" || lower == "delete last word") {
            return VoiceCommandResult(processedText = "", isDeleteLastWordTriggered = true)
        }

        var copyTriggered = false
        var deleteTriggered = false

        // 2. Check if utterance ends with "কপি করো" / "copy that"
        val copySuffixes = listOf("কপি করো", "কপি কর", "copy that", "copy now")
        for (suffix in copySuffixes) {
            val normSuffix = Normalizer.normalize(suffix, Normalizer.Form.NFC)
            if (result.endsWith(normSuffix, ignoreCase = true)) {
                result = result.substring(0, result.length - normSuffix.length).trim()
                copyTriggered = true
                break
            }
        }

        // 3. Check if utterance ends with "মুছো" / "delete that"
        val deleteLastWordSuffixes = listOf("মুছো", "মুছে ফেলো", "delete that", "delete last word")
        for (suffix in deleteLastWordSuffixes) {
            val normSuffix = Normalizer.normalize(suffix, Normalizer.Form.NFC)
            if (result.endsWith(normSuffix, ignoreCase = true)) {
                val base = result.substring(0, result.length - normSuffix.length).trim()
                if (base.isBlank()) {
                    result = ""
                    deleteTriggered = true
                } else {
                    val lastSpace = base.lastIndexOf(' ')
                    result = if (lastSpace >= 0) base.substring(0, lastSpace).trim() else ""
                }
                break
            }
        }

        // 4. Safe Punctuation replacements with strict word-boundary matching
        for ((spoken, sym) in punctuations) {
            val escaped = Regex.escape(spoken)
            if (sym in listOf("।", ",", "?", ".")) {
                // Attach punctuation to the preceding word, consuming preceding whitespace if present
                val pat = Regex("(?i)(?:\\s+|^)" + escaped + "(?=\\s|$|[।,?!.])")
                result = result.replace(pat, sym)
            } else {
                // Structural break like \n or \n\n: preserve boundary
                val pat = Regex("(?i)(?:(?<=\\s)|(?<=^))" + escaped + "(?=\\s|$|[।,?!.])")
                result = result.replace(pat, sym)
            }
        }

        // 5. Format spacing: attach punctuation directly to preceding word
        result = result.replace(Regex("\\s+([।,?.])"), "$1")
        // Ensure space after punctuation if immediately followed by another word
        result = result.replace(Regex("([।,?.])(?=[^\\s।,?.])"), "$1 ")
        // Format clean newlines
        result = result.replace(Regex("[ \\t]+(\\n)"), "$1")
        result = result.replace(Regex("(\\n)[ \\t]+"), "$1")

        return VoiceCommandResult(
            processedText = result.trim(),
            isCopyCommandTriggered = copyTriggered,
            isClearAllTriggered = false,
            isDeleteLastWordTriggered = deleteTriggered
        )
    }
}
