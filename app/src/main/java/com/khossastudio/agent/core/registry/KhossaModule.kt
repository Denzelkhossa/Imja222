package com.khossastudio.agent.core.registry

/**
 * Base interface for all KhossaAgent modules.
 * Each module must implement lifecycle methods and declare its dependencies.
 */
interface KhossaModule {
    val name: String
    val version: String
    val dependencies: List<String> get() = emptyList()
    
    suspend fun initialize(context: android.content.Context): Result<Unit>
    suspend fun start(): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun reset(): Result<Unit>
    
    fun isInitialized(): Boolean
    fun isRunning(): Boolean
}
