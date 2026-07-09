package com.khossastudio.agent.core

import android.content.Context
import com.khossastudio.agent.core.config.AppConfig
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import com.khossastudio.agent.core.registry.ModuleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Core module - central orchestration layer.
 * Manages app state, lifecycle, and module coordination.
 */
class CoreModule : KhossaModule {
    override val name = "core"
    override val version = "1.0.0"
    override val dependencies: List<String> = emptyList()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInit = false
    private var isRun = false
    private lateinit var context: Context
    
    private val _state = MutableStateFlow(CoreState.IDLE)
    val state: StateFlow<CoreState> = _state.asStateFlow()
    
    private val _mode = MutableStateFlow(AppMode.SUPERVISED)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()
    
    enum class CoreState { IDLE, INITIALIZING, READY, RUNNING, ERROR, STOPPED }
    enum class AppMode { SUPERVISED, AUTONOMOUS, LIVE, OFFLINE }
    
    override suspend fun initialize(context: Context): Result<Unit> = runCatching {
        this.context = context.applicationContext
        Logger.d("CoreModule", "Initializing...")
        
        // Load configuration
        AppConfig.load(context)
        
        isInit = true
        _state.value = CoreState.READY
        Logger.d("CoreModule", "Initialized successfully")
        EventBus.publish(EventBus.ModuleEvent(name, "initialized"))
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        Logger.d("CoreModule", "Starting...")
        _state.value = CoreState.RUNNING
        isRun = true
        
        // Subscribe to relevant events
        scope.launch {
            EventBus.events.collect { event ->
                handleEvent(event)
            }
        }
        
        Logger.d("CoreModule", "Started successfully")
        EventBus.publish(EventBus.ModuleEvent(name, "started"))
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        Logger.d("CoreModule", "Stopping...")
        _state.value = CoreState.STOPPED
        isRun = false
        EventBus.publish(EventBus.ModuleEvent(name, "stopped"))
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        Logger.d("CoreModule", "Resetting...")
        _state.value = CoreState.IDLE
        isInit = false
        isRun = false
        EventBus.publish(EventBus.ModuleEvent(name, "reset"))
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun setMode(newMode: AppMode) {
        _mode.value = newMode
        EventBus.publish(EventBus.ModuleEvent(name, "mode_changed", mapOf("mode" to newMode.name)))
    }
    
    fun getContext() = context
    
    private fun handleEvent(event: EventBus.KhossaEvent) {
        when (event) {
            is EventBus.CommandExecutedEvent -> {
                Logger.d("CoreModule", "Command executed: ${event.command}, success=${event.success}")
            }
            is EventBus.PermissionChangedEvent -> {
                Logger.d("CoreModule", "Permission ${event.permission} changed: ${event.granted}")
            }
            else -> { /* Handle other events */ }
        }
    }
}
