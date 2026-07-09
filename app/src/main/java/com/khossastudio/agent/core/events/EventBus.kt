package com.khossastudio.agent.core.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Central event bus for inter-module communication.
 * Supports typed events with filtering by tag/topic.
 */
object EventBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _events = MutableSharedFlow<KhossaEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<KhossaEvent> = _events.asSharedFlow()
    
    private val subscribers = mutableMapOf<String, MutableSet<suspend (KhossaEvent) -> Unit>>()
    
    /**
     * Base sealed class for all KhossaAgent events.
     */
    sealed class KhossaEvent {
        abstract val tag: String
        abstract val timestamp: Long
    }
    
    // Voice Events
    data class VoiceHeardEvent(val text: String, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "voice.heard"
    }
    
    data class WakeWordDetectedEvent(override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "voice.wakeword"
    }
    
    // Command Events
    data class CommandReceivedEvent(val command: String, val isOffline: Boolean, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "command.received"
    }
    
    data class CommandExecutedEvent(val command: String, val success: Boolean, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "command.executed"
    }
    
    // System Events
    data class PermissionChangedEvent(val permission: String, val granted: Boolean, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "permission.changed"
    }
    
    data class ContextChangedEvent(val context: Map<String, Any>, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "context.changed"
    }
    
    data class DeviceStateChangedEvent(val state: Map<String, Any>, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "device.state"
    }
    
    // Automation Events
    data class AutomationTriggeredEvent(val automationId: String, val condition: String, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "automation.triggered"
    }
    
    // Module Events
    data class ModuleEvent(val moduleName: String, val event: String, val data: Map<String, Any>? = null, override val timestamp: Long = System.currentTimeMillis()) : KhossaEvent() {
        override val tag = "module.$moduleName"
    }
    
    /**
     * Publish an event to all subscribers.
     */
    fun publish(event: KhossaEvent) {
        scope.launch {
            _events.emit(event)
        }
    }
    
    /**
     * Subscribe to events by tag pattern.
     * @param tagPattern e.g., "voice.*" or "command.received"
     * @param callback Function to handle matching events
     * @return Subscription ID for unsubscribing
     */
    fun subscribe(tagPattern: String, callback: suspend (KhossaEvent) -> Unit): String {
        val id = "${tagPattern}_${System.currentTimeMillis()}"
        subscribers.getOrPut(tagPattern) { mutableSetOf() }.add(callback)
        return id
    }
    
    /**
     * Unsubscribe by ID.
     */
    fun unsubscribe(id: String) {
        subscribers.values.forEach { it.removeIf { cb -> cb.toString().contains(id) } }
    }
    
    /**
     * Observe events matching a tag pattern.
     */
    fun observe(tagPattern: String): SharedFlow<KhossaEvent> {
        val flow = MutableSharedFlow<KhossaEvent>(extraBufferCapacity = 50)
        val regex = tagPattern.replace("*", ".*").toRegex()
        scope.launch {
            events.collect { event ->
                if (regex.matches(event.tag)) {
                    flow.emit(event)
                }
            }
        }
        return flow
    }
}
