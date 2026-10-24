package com.nirzor.voicebubble.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flag: String
)

object SupportedLanguages {
    val languages = listOf(
        VoiceLanguage("bn-BD", "Bangla (Bangladesh)", "বাংলা (বাংলাদেশ)", "🇧🇩"),
        VoiceLanguage("bn-IN", "Bangla (India)", "বাংলা (ভারত)", "🇮🇳"),
        VoiceLanguage("en-US", "English (United States)", "English (US)", "🇺🇸"),
        VoiceLanguage("en-GB", "English (United Kingdom)", "English (UK)", "🇬🇧"),
        VoiceLanguage("hi-IN", "Hindi (India)", "हिन्दी (ভারত)", "🇮🇳"),
        VoiceLanguage("ar-SA", "Arabic (Saudi Arabia)", "العربية", "🇸🇦"),
        VoiceLanguage("es-ES", "Spanish (Spain)", "Español", "🇪🇸"),
        VoiceLanguage("fr-FR", "French (France)", "Français", "🇫🇷"),
        VoiceLanguage("default", "Auto / System Default", "সিস্টেম ডিফল্ট", "🌐")
    )

    fun getLanguageByCode(code: String): VoiceLanguage {
        return languages.find { it.code == code } ?: languages[0]
    }
}

enum class PolishStyle(val key: String, val title: String, val description: String) {
    STRICT_GRAMMAR("strict_grammar", "গুছিয়ে দাও (Grammar)", "যথাযথ ব্যাকরণ ও প্রমিত লিখিত রূপ"),
    CASUAL_MESSAGING("casual_messaging", "ক্যাজুয়াল (Casual)", "বন্ধুত্বপূর্ণ ও অনানুষ্ঠানিক মেসেজিং রূপ"),
    CODE_HYBRID("code_hybrid", "কোড/ইংরেজি হাইব্রিড", "টেকনিক্যাল শব্দ ও কোড/ইংরেজি অক্ষত রাখুন"),
    PARAPHRASE("paraphrase", "স্মার্ট প্যারাফ্রেজ", "একই অর্থ রেখে আরও সুন্দরভাবে সাজিয়ে লেখা"),
    SHORTEN("shorten", "সংক্ষেপ করো (Shorten)", "মূল কথা রেখে সংক্ষিপ্ত সারাংশ"),
    EXPAND("expand", "বিস্তারিত করো (Expand)", "প্রাসঙ্গিক বিবরণসহ প্রাঞ্জল সম্প্রসারণ"),
    FORMAL("formal", "আনুষ্ঠানিক (Formal)", "অফিশিয়াল ইমেইল বা দাপ্তরিক ভাষা"),
    TRANSLATE_BN_EN("translate_bn_en", "অনুবাদ (বাংলা ↔ ইংরেজি)", "বাংলা বললে ইংরেজিতে এবং ইংরেজি বললে বাংলায় রূপান্তর"),
    BULLET_POINTS("bullet_points", "বুলেট পয়েন্ট", "বক্তব্যকে পরিষ্কার বুলেট পয়েন্ট আকারে সাজানো"),
    CUSTOM("custom", "কাস্টম প্রম্পট (Custom)", "আপনার নিজের লেখা নির্দেশনা অনুযায়ী রূপান্তর");

    val displayName: String get() = title

    companion object {
        fun fromKey(key: String): PolishStyle = entries.firstOrNull { it.key == key } ?: STRICT_GRAMMAR
    }
}

enum class TapMode(val key: String, val title: String, val description: String) {
    TAP_TO_TOGGLE("tap_to_toggle", "ট্যাপে শুরু/শেষ (Tap to Toggle)", "একবার ট্যাপে রেকর্ডিং শুরু, আবার ট্যাপে শেষ। মেনু দেখতে চেপে ধরে রাখুন।"),
    PUSH_TO_TALK("push_to_talk", "চেপে ধরে কথা (Push to Talk)", "বাবল চেপে ধরে কথা বলুন, আঙুল ছেড়ে দিলে শেষ। মেনু দেখতে ডাবল-ট্যাপ করুন।");

    val displayName: String get() = title

    companion object {
        fun fromKey(key: String): TapMode = entries.firstOrNull { it.key == key } ?: TAP_TO_TOGGLE
    }
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voice_bubble_prefs", Context.MODE_PRIVATE)

    private val _selectedLanguage = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "bn-BD") ?: "bn-BD")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    // Bubble size clamped between 48 and 100 dp
    private val rawStoredSize = prefs.getInt(KEY_BUBBLE_SIZE, 60).coerceIn(48, 100)
    private val _bubbleSizeDp = MutableStateFlow(rawStoredSize)
    val bubbleSizeDp: StateFlow<Int> = _bubbleSizeDp.asStateFlow()

    private val _bubbleOpacity = MutableStateFlow(prefs.getFloat(KEY_BUBBLE_OPACITY, 0.95f))
    val bubbleOpacity: StateFlow<Float> = _bubbleOpacity.asStateFlow()

    private val _bubbleColorTheme = MutableStateFlow(prefs.getString(KEY_BUBBLE_COLOR_THEME, "indigo_ocean") ?: "indigo_ocean")
    val bubbleColorTheme: StateFlow<String> = _bubbleColorTheme.asStateFlow()

    private val _hapticFeedback = MutableStateFlow(prefs.getBoolean(KEY_HAPTIC, true))
    val hapticFeedback: StateFlow<Boolean> = _hapticFeedback.asStateFlow()

    private val _soundFeedback = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    val soundFeedback: StateFlow<Boolean> = _soundFeedback.asStateFlow()

    private val _autoCopy = MutableStateFlow(prefs.getBoolean(KEY_AUTO_COPY, true))
    val autoCopy: StateFlow<Boolean> = _autoCopy.asStateFlow()

    private val _dockToEdge = MutableStateFlow(prefs.getBoolean(KEY_DOCK_TO_EDGE, true))
    val dockToEdge: StateFlow<Boolean> = _dockToEdge.asStateFlow()

    private val _keepBubbleAlwaysOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_ALWAYS_ON, true))
    val keepBubbleAlwaysOn: StateFlow<Boolean> = _keepBubbleAlwaysOn.asStateFlow()

    private val _startListeningFromTile = MutableStateFlow(prefs.getBoolean(KEY_START_FROM_TILE, true))
    val startListeningFromTile: StateFlow<Boolean> = _startListeningFromTile.asStateFlow()

    private val _aiPolishEnabled = MutableStateFlow(prefs.getBoolean(KEY_AI_POLISH, true))
    val aiPolishEnabled: StateFlow<Boolean> = _aiPolishEnabled.asStateFlow()

    private val _polishStyle = MutableStateFlow(
        PolishStyle.fromKey(prefs.getString(KEY_POLISH_STYLE, PolishStyle.STRICT_GRAMMAR.key) ?: PolishStyle.STRICT_GRAMMAR.key)
    )
    val polishStyle: StateFlow<PolishStyle> = _polishStyle.asStateFlow()

    private val _directGeminiAudio = MutableStateFlow(prefs.getBoolean(KEY_DIRECT_GEMINI_AUDIO, false))
    val directGeminiAudio: StateFlow<Boolean> = _directGeminiAudio.asStateFlow()

    private val _historyRetentionDays = MutableStateFlow(prefs.getInt(KEY_RETENTION_DAYS, 30))
    val historyRetentionDays: StateFlow<Int> = _historyRetentionDays.asStateFlow()

    private val _bubbleX = MutableStateFlow(prefs.getInt(KEY_BUBBLE_X, -1))
    val bubbleX: StateFlow<Int> = _bubbleX.asStateFlow()

    private val _bubbleY = MutableStateFlow(prefs.getInt(KEY_BUBBLE_Y, -1))
    val bubbleY: StateFlow<Int> = _bubbleY.asStateFlow()

    private val _lastUpdateCheckTime = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L))
    val lastUpdateCheckTime: StateFlow<Long> = _lastUpdateCheckTime.asStateFlow()

    private val _lastInstalledVersionCode = MutableStateFlow(prefs.getInt(KEY_LAST_INSTALLED_VER, 0))
    val lastInstalledVersionCode: StateFlow<Int> = _lastInstalledVersionCode.asStateFlow()

    private val _continuousMode = MutableStateFlow(prefs.getBoolean(KEY_CONTINUOUS_MODE, false))
    val continuousMode: StateFlow<Boolean> = _continuousMode.asStateFlow()

    private val _autoPunctuation = MutableStateFlow(prefs.getBoolean(KEY_AUTO_PUNCTUATION, true))
    val autoPunctuation: StateFlow<Boolean> = _autoPunctuation.asStateFlow()

    private val _customPolishPrompt = MutableStateFlow(prefs.getString(KEY_CUSTOM_POLISH_PROMPT, "") ?: "")
    val customPolishPrompt: StateFlow<String> = _customPolishPrompt.asStateFlow()

    private val _recentLanguages = MutableStateFlow(
        (prefs.getString(KEY_RECENT_LANGUAGES, "bn-BD,en-US") ?: "bn-BD,en-US").split(",")
    )
    val recentLanguages: StateFlow<List<String>> = _recentLanguages.asStateFlow()

    private val _directInject = MutableStateFlow(prefs.getBoolean(KEY_DIRECT_INJECT, false))
    val directInject: StateFlow<Boolean> = _directInject.asStateFlow()

    private val _tapMode = MutableStateFlow(
        TapMode.fromKey(prefs.getString(KEY_TAP_MODE, TapMode.TAP_TO_TOGGLE.key) ?: TapMode.TAP_TO_TOGGLE.key)
    )
    val tapMode: StateFlow<TapMode> = _tapMode.asStateFlow()

    init {
        // Enforce 48dp minimum in storage
        if (prefs.getInt(KEY_BUBBLE_SIZE, 60) < 48) {
            prefs.edit().putInt(KEY_BUBBLE_SIZE, 48).apply()
        }
    }

    fun setLanguage(code: String) {
        setSelectedLanguage(code)
    }

    fun setSelectedLanguage(code: String) {
        val current = _selectedLanguage.value
        if (current != code && code != "default") {
            val updated = listOf(code, current).distinct().take(2)
            _recentLanguages.value = updated
            prefs.edit().putString(KEY_RECENT_LANGUAGES, updated.joinToString(",")).apply()
        }
        _selectedLanguage.value = code
        prefs.edit().putString(KEY_LANGUAGE, code).apply()
    }

    fun setContinuousMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, enabled).apply()
        _continuousMode.value = enabled
    }

    fun setAutoPunctuation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PUNCTUATION, enabled).apply()
        _autoPunctuation.value = enabled
    }

    fun setCustomPolishPrompt(prompt: String) {
        prefs.edit().putString(KEY_CUSTOM_POLISH_PROMPT, prompt).apply()
        _customPolishPrompt.value = prompt
    }

    fun setDirectInject(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIRECT_INJECT, enabled).apply()
        _directInject.value = enabled
    }

    fun setTapMode(mode: TapMode) {
        prefs.edit().putString(KEY_TAP_MODE, mode.key).apply()
        _tapMode.value = mode
    }

    fun setBubbleSize(sizeDp: Int) {
        val clamped = sizeDp.coerceIn(48, 100)
        prefs.edit().putInt(KEY_BUBBLE_SIZE, clamped).apply()
        _bubbleSizeDp.value = clamped
    }

    fun setBubbleOpacity(opacity: Float) {
        prefs.edit().putFloat(KEY_BUBBLE_OPACITY, opacity).apply()
        _bubbleOpacity.value = opacity
    }

    fun setBubbleColorTheme(themeId: String) {
        prefs.edit().putString(KEY_BUBBLE_COLOR_THEME, themeId).apply()
        _bubbleColorTheme.value = themeId
    }

    fun getSelectedColorTheme(): BubbleTheme {
        return BubbleThemes.getThemeById(_bubbleColorTheme.value)
    }

    fun setHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply()
        _hapticFeedback.value = enabled
    }

    fun setSoundFeedback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
        _soundFeedback.value = enabled
    }

    fun setAutoCopy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_COPY, enabled).apply()
        _autoCopy.value = enabled
    }

    fun setDockToEdge(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOCK_TO_EDGE, enabled).apply()
        _dockToEdge.value = enabled
    }

    fun setKeepBubbleAlwaysOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALWAYS_ON, enabled).apply()
        _keepBubbleAlwaysOn.value = enabled
    }

    fun setStartListeningFromTile(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_START_FROM_TILE, enabled).apply()
        _startListeningFromTile.value = enabled
    }

    fun setAiPolishEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_POLISH, enabled).apply()
        _aiPolishEnabled.value = enabled
    }

    fun setPolishStyle(style: PolishStyle) {
        prefs.edit().putString(KEY_POLISH_STYLE, style.key).apply()
        _polishStyle.value = style
    }

    fun setDirectGeminiAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIRECT_GEMINI_AUDIO, enabled).apply()
        _directGeminiAudio.value = enabled
    }

    fun setHistoryRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        _historyRetentionDays.value = days
    }

    fun setBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
        _bubbleX.value = x
        _bubbleY.value = y
    }

    fun setLastUpdateCheckTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, timestamp).apply()
        _lastUpdateCheckTime.value = timestamp
    }

    fun setLastInstalledVersionCode(code: Int) {
        prefs.edit().putInt(KEY_LAST_INSTALLED_VER, code).apply()
        _lastInstalledVersionCode.value = code
    }

    companion object {
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_BUBBLE_SIZE = "key_bubble_size"
        private const val KEY_BUBBLE_OPACITY = "key_bubble_opacity"
        private const val KEY_BUBBLE_COLOR_THEME = "key_bubble_color_theme"
        private const val KEY_HAPTIC = "key_haptic"
        private const val KEY_SOUND = "key_sound"
        private const val KEY_AUTO_COPY = "key_auto_copy"
        private const val KEY_DOCK_TO_EDGE = "key_dock_to_edge"
        private const val KEY_KEEP_ALWAYS_ON = "key_keep_always_on"
        private const val KEY_START_FROM_TILE = "key_start_from_tile"
        private const val KEY_AI_POLISH = "key_ai_polish"
        private const val KEY_POLISH_STYLE = "key_polish_style"
        private const val KEY_DIRECT_GEMINI_AUDIO = "key_direct_gemini_audio"
        private const val KEY_RETENTION_DAYS = "key_retention_days"
        private const val KEY_BUBBLE_X = "key_bubble_x"
        private const val KEY_BUBBLE_Y = "key_bubble_y"
        private const val KEY_LAST_UPDATE_CHECK = "key_last_update_check"
        private const val KEY_LAST_INSTALLED_VER = "key_last_installed_ver"
        private const val KEY_CONTINUOUS_MODE = "key_continuous_mode"
        private const val KEY_AUTO_PUNCTUATION = "key_auto_punctuation"
        private const val KEY_CUSTOM_POLISH_PROMPT = "key_custom_polish_prompt"
        private const val KEY_RECENT_LANGUAGES = "key_recent_languages"
        private const val KEY_DIRECT_INJECT = "key_direct_inject"
        private const val KEY_TAP_MODE = "key_tap_mode"

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = AppPreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
