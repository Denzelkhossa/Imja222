package com.khossastudio.agent.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Centralized logging system for KhossaAgent.
 * Supports file logging, in-memory buffer, and log levels.
 */
object Logger {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }
    
    private const val TAG = "KhossaAgent"
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_BUFFER = 500
    
    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val module: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private var minLevel = Level.DEBUG
    private var isDebug = true
    
    fun setMinLevel(level: Level) { minLevel = level }
    fun setDebug(enabled: Boolean) { isDebug = enabled }
    
    fun v(module: String, message: String) = log(Level.VERBOSE, module, message)
    fun d(module: String, message: String) = log(Level.DEBUG, module, message)
    fun i(module: String, message: String) = log(Level.INFO, module, message)
    fun w(module: String, message: String) = log(Level.WARN, module, message)
    fun e(module: String, message: String, throwable: Throwable? = null) = log(Level.ERROR, module, message, throwable)
    
    private fun log(level: Level, module: String, message: String, throwable: Throwable? = null) {
        if (level.ordinal < minLevel.ordinal) return
        
        val entry = LogEntry(System.currentTimeMillis(), level, module, message, throwable)
        
        // Add to buffer
        buffer.offer(entry)
        while (buffer.size > MAX_BUFFER) buffer.poll()
        
        // Update state
        _logs.value = buffer.toList()
        
        // Log to Android
        if (isDebug) {
            val tag = "$TAG:$module"
            when (level) {
                Level.VERBOSE -> Log.v(tag, message)
                Level.DEBUG -> Log.d(tag, message)
                Level.INFO -> Log.i(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message, throwable)
            }
        }
    }
    
    fun getLogs(module: String? = null, level: Level? = null, limit: Int = 100): List<LogEntry> {
        return buffer.toList()
            .filter { entry -> module == null || entry.module == module }
            .filter { entry -> level == null || entry.level == level }
            .takeLast(limit)
    }
    
    fun clear() {
        buffer.clear()
        _logs.value = emptyList()
    }
}
