package com.khossastudio.agent.core.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central configuration manager for KhossaAgent.
 * Handles all app settings with reactive updates.
 */
object AppConfig {
    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val config: StateFlow<Map<String, Any?>> = _config.asStateFlow()
    
    // Config keys
    object Keys {
        const val PROVIDER = "llm_provider"
        const val MODEL = "llm_model"
        const val API_KEYS = "api_keys"
        const val WAKE_WORD = "wake_word"
        const val OFFLINE_MODE = "offline_mode"
        const val AUTO_START = "auto_start"
        const val DARK_MODE = "dark_mode"
        const val LANGUAGE = "language"
        const val SUPERVISED_MODE = "supervised_mode"
        const val LIVE_MODE = "live_mode"
        const val MAX_STEPS = "max_steps"
        const val RESPONSE_TIMEOUT = "response_timeout"
        const val EDGE_AI_ENABLED = "edge_ai_enabled"
        const val EDGE_AI_THRESHOLD_MS = "edge_ai_threshold_ms"
    }
    
    // Defaults
    private val defaults = mapOf(
        Keys.PROVIDER to "gemini",
        Keys.MODEL to "gemini-2.0-flash",
        Keys.WAKE_WORD to "Olá, Khossa",
        Keys.OFFLINE_MODE to false,
        Keys.AUTO_START to false,
        Keys.DARK_MODE to false,
        Keys.LANGUAGE to "pt-MZ",
        Keys.SUPERVISED_MODE to true,
        Keys.LIVE_MODE to false,
        Keys.MAX_STEPS to 25,
        Keys.RESPONSE_TIMEOUT to 30000L,
        Keys.EDGE_AI_ENABLED to true,
        Keys.EDGE_AI_THRESHOLD_MS to 100L
    )
    
    fun load(context: Context) {
        prefs = context.getSharedPreferences("khossa_config", Context.MODE_PRIVATE)
        refresh()
    }
    
    fun refresh() {
        _config.value = defaults.mapValues { (key, default) ->
            when (default) {
                is Boolean -> prefs.getBoolean(key, default)
                is Int -> prefs.getInt(key, default)
                is Long -> prefs.getLong(key, default)
                is String -> prefs.getString(key, default)
                is Float -> prefs.getFloat(key, default)
                else -> default
            }
        }
    }
    
    fun getString(key: String): String? = _config.value[key] as? String ?: defaults[key] as? String
    fun getInt(key: String): Int = (_config.value[key] as? Int) ?: (defaults[key] as? Int) ?: 0
    fun getLong(key: String): Long = (_config.value[key] as? Long) ?: (defaults[key] as? Long) ?: 0L
    fun getBoolean(key: String): Boolean = (_config.value[key] as? Boolean) ?: (defaults[key] as? Boolean) ?: false
    fun getFloat(key: String): Float = (_config.value[key] as? Float) ?: (defaults[key] as? Float) ?: 0f
    
    fun set(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Float -> putFloat(key, value)
            }
            apply()
        }
        refresh()
    }
    
    fun getApiKeys(provider: String): List<String> {
        val key = "${Keys.API_KEYS}_$provider"
        return prefs.getString(key, "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
    }
    
    fun setApiKeys(provider: String, keys: List<String>) {
        val key = "${Keys.API_KEYS}_$provider"
        prefs.edit().putString(key, keys.joinToString("\n")).apply()
    }
}
