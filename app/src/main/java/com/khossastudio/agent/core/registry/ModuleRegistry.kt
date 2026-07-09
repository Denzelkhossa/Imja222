package com.khossastudio.agent.core.registry

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central registry for all KhossaAgent modules.
 * Manages module lifecycle, dependencies, and communication.
 */
object ModuleRegistry {
    private val modules = mutableMapOf<String, KhossaModule>()
    private val _moduleStates = MutableStateFlow<Map<String, ModuleState>>(emptyMap())
    val moduleStates: StateFlow<Map<String, ModuleState>> = _moduleStates.asStateFlow()
    
    data class ModuleState(
        val name: String,
        val isInitialized: Boolean,
        val isRunning: Boolean,
        val version: String
    )
    
    fun register(module: KhossaModule) {
        modules[module.name] = module
        updateState(module)
    }
    
    fun unregister(name: String) {
        kotlinx.coroutines.runBlocking { modules[name]?.stop() }
        modules.remove(name)
        _moduleStates.value = _moduleStates.value.filterKeys { it != name }
    }
    
    fun get(name: String): KhossaModule? = modules[name]
    
    fun getAll(): List<KhossaModule> = modules.values.toList()
    
    suspend fun initializeAll(context: Context): Result<Unit> {
        val sorted = topologicalSort()
        for (module in sorted) {
            val result = module.initialize(context)
            if (result.isFailure) return result
        }
        return Result.success(Unit)
    }
    
    suspend fun startAll(): Result<Unit> {
        for (module in modules.values) {
            if (module.isInitialized()) {
                val result = module.start()
                if (result.isFailure) return result
                updateState(module)
            }
        }
        return Result.success(Unit)
    }
    
    suspend fun stopAll(): Result<Unit> {
        for (module in modules.values.toList().asReversed()) {
            kotlinx.coroutines.runBlocking { module.stop() }
            updateState(module)
        }
        return Result.success(Unit)
    }
    
    private fun topologicalSort(): List<KhossaModule> {
        val sorted = mutableListOf<KhossaModule>()
        val visited = mutableSetOf<String>()
        val temp = mutableSetOf<String>()
        
        fun visit(module: KhossaModule) {
            if (module.name in visited) return
            if (module.name in temp) throw IllegalStateException("Circular dependency detected: ${module.name}")
            temp.add(module.name)
            module.dependencies.forEach { depName ->
                modules[depName]?.let { visit(it) }
            }
            temp.remove(module.name)
            visited.add(module.name)
            sorted.add(module)
        }
        
        modules.values.forEach { visit(it) }
        return sorted
    }
    
    private fun updateState(module: KhossaModule) {
        _moduleStates.value = _moduleStates.value + (module.name to ModuleState(
            name = module.name,
            isInitialized = module.isInitialized(),
            isRunning = module.isRunning(),
            version = module.version
        ))
    }
}
