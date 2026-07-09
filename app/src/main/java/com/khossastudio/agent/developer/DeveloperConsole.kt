package com.khossastudio.agent.developer

import android.content.Context
import com.khossastudio.agent.capabilities.DeviceCapabilityManager
import com.khossastudio.agent.core.config.AppConfig
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import com.khossastudio.agent.core.registry.ModuleRegistry
import com.khossastudio.agent.edgeai.EdgeAIEngine
import com.khossastudio.agent.memory.AgentDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer Console - Internal panel for debugging and monitoring.
 * Shows logs, sensors, permissions, skills, memory usage, LLM calls, etc.
 */
class DeveloperConsole : KhossaModule {
    override val name = "developer"
    override val version = "1.0.0"
    override val dependencies = listOf("core")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private val _stats = MutableStateFlow<ConsoleStats>(ConsoleStats())
    val stats: StateFlow<ConsoleStats> = _stats.asStateFlow()
    
    private val _performanceMetrics = MutableStateFlow<PerformanceMetrics>(PerformanceMetrics())
    val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics.asStateFlow()
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val module: String,
        val message: String
    )
    
    data class ConsoleStats(
        val totalModules: Int = 0,
        val runningModules: Int = 0,
        val totalSkills: Int = 0,
        val totalAutomations: Int = 0,
        val memoryUsageMb: Float = 0f,
        val uptime: Long = 0
    )
    
    data class PerformanceMetrics(
        val avgResponseTimeMs: Long = 0,
        val minResponseTimeMs: Long = 0,
        val maxResponseTimeMs: Long = 0,
        val totalLlmCalls: Int = 0,
        val failedLlmCalls: Int = 0,
        val edgeAiHits: Int = 0,
        val cloudCalls: Int = 0,
        val lastLlmCall: Long? = null
    )
    
    private var capabilityManager: DeviceCapabilityManager? = null
    private var edgeAIEngine: EdgeAIEngine? = null
    private var startTime: Long = 0
    
    private var llmCallCount = 0
    private var failedLlmCalls = 0
    private var edgeAiHits = 0
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        startTime = System.currentTimeMillis()
        
        // Subscribe to events for monitoring
        EventBus.subscribe("llm.*") { event ->
            when (event) {
                is EventBus.ModuleEvent -> {
                    if (event.event == "llm_call") llmCallCount++
                    if (event.event == "llm_error") failedLlmCalls++
                }
                else -> {}
            }
        }
        
        EventBus.subscribe("command.*") { event ->
            when (event) {
                is EventBus.CommandReceivedEvent -> {
                    if (event.isOffline) edgeAiHits++
                }
                else -> {}
            }
        }
        
        isInit = true
        Logger.d(name, "DeveloperConsole initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        startTime = System.currentTimeMillis()
        refreshStats()
        Logger.d(name, "DeveloperConsole started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        Logger.d(name, "DeveloperConsole stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        _logs.value = emptyList()
        _stats.value = ConsoleStats()
        _performanceMetrics.value = PerformanceMetrics()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun setCapabilityManager(cm: DeviceCapabilityManager) {
        capabilityManager = cm
    }
    
    fun setEdgeAIEngine(ee: EdgeAIEngine) {
        edgeAIEngine = ee
    }
    
    fun recordLlmCall(success: Boolean, responseTimeMs: Long) {
        llmCallCount++
        if (!success) failedLlmCalls++
        
        val current = _performanceMetrics.value
        _performanceMetrics.value = current.copy(
            avgResponseTimeMs = ((current.avgResponseTimeMs * (llmCallCount - 1)) + responseTimeMs) / llmCallCount,
            minResponseTimeMs = minOf(current.minResponseTimeMs.takeIf { it > 0 } ?: responseTimeMs, responseTimeMs),
            maxResponseTimeMs = maxOf(current.maxResponseTimeMs, responseTimeMs),
            totalLlmCalls = llmCallCount,
            failedLlmCalls = failedLlmCalls,
            lastLlmCall = System.currentTimeMillis()
        )
    }
    
    fun refreshStats() {
        val modules = ModuleRegistry.getAll()
        val running = modules.count { it.isRunning() }
        
        val memory = Runtime.getRuntime()
        val usedMb = (memory.totalMemory() - memory.freeMemory()) / (1024 * 1024)
        
        _stats.value = ConsoleStats(
            totalModules = modules.size,
            runningModules = running,
            totalSkills = 13, // Built-in skills count
            totalAutomations = 0,
            memoryUsageMb = usedMb.toFloat(),
            uptime = System.currentTimeMillis() - startTime
        )
        
        // Get logs from Logger
        val loggerLogs = Logger.getLogs(limit = 200)
        _logs.value = loggerLogs.map { entry ->
            LogEntry(
                timestamp = entry.timestamp,
                level = entry.level.name,
                module = entry.module,
                message = entry.message
            )
        }
    }
    
    fun getSystemInfo(): Map<String, Any> {
        val caps = capabilityManager?.capabilities?.value
        val battery = capabilityManager?.batteryInfo?.value
        val memory = Runtime.getRuntime()
        
        return mapOf(
            "device" to mapOf(
                "available_memory_mb" to memory.maxMemory() / (1024 * 1024),
                "used_memory_mb" to (memory.totalMemory() - memory.freeMemory()) / (1024 * 1024),
                "processors" to Runtime.getRuntime().availableProcessors()
            ),
            "sensors" to (caps?.sensors?.size ?: 0),
            "cameras" to ( caps?.cameraCount ?: 0),
            "battery_level" to (battery?.level ?: 0),
            "battery_charging" to (battery?.isCharging ?: false)
        )
    }
    
    fun getDebugReport(): String {
        val sb = StringBuilder()
        val stats = _stats.value
        val perf = _performanceMetrics.value
        val uptime = System.currentTimeMillis() - startTime
        
        sb.appendLine("=== KhossaAgent Debug Report ===")
        sb.appendLine()
        sb.appendLine("--- System ---")
        sb.appendLine("Uptime: ${uptime / 1000}s")
        sb.appendLine("Memory: ${stats.memoryUsageMb}MB")
        sb.appendLine()
        
        sb.appendLine("--- Modules ---")
        sb.appendLine("Total: ${stats.totalModules}")
        sb.appendLine("Running: ${stats.runningModules}")
        sb.appendLine()
        
        sb.appendLine("--- LLM Performance ---")
        sb.appendLine("Total Calls: ${perf.totalLlmCalls}")
        sb.appendLine("Failed: ${perf.failedLlmCalls}")
        sb.appendLine("Avg Response: ${perf.avgResponseTimeMs}ms")
        sb.appendLine()
        
        sb.appendLine("--- Edge AI ---")
        sb.appendLine("Offline Hits: ${perf.edgeAiHits}")
        sb.appendLine("Cloud Calls: ${perf.totalLlmCalls - edgeAiHits}")
        sb.appendLine()
        
        sb.appendLine("--- Configuration ---")
        sb.appendLine("Provider: ${AppConfig.getString(AppConfig.Keys.PROVIDER)}")
        sb.appendLine("Model: ${AppConfig.getString(AppConfig.Keys.MODEL)}")
        sb.appendLine("Edge AI: ${AppConfig.getBoolean(AppConfig.Keys.EDGE_AI_ENABLED)}")
        
        return sb.toString()
    }
    
    fun exportLogs(): String {
        return _logs.value.joinToString("\n") { entry ->
            "${entry.timestamp} ${entry.level} ${entry.module}: ${entry.message}"
        }
    }
}
