package com.khossastudio.agent.automation

import android.content.Context
import android.content.SharedPreferences
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import com.khossastudio.agent.context.ContextAwareness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Automation Engine - SE condição → ENTÃO ação.
 * Creates and executes automations based on context triggers.
 */
class AutomationEngine : KhossaModule {
    override val name = "automation"
    override val version = "1.0.0"
    override val dependencies = listOf("core", "context", "capabilities")
    
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var isInit = false
    private var isRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()
    
    private val _enabledAutomations = MutableStateFlow<Set<String>>(emptySet())
    val enabledAutomations: StateFlow<Set<String>> = _enabledAutomations.asStateFlow()
    
    private val _lastTriggered = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastTriggered: StateFlow<Map<String, Long>> = _lastTriggered.asStateFlow()
    
    private var contextAwareness: ContextAwareness? = null
    
    data class Automation(
        val id: String,
        val name: String,
        val description: String,
        val trigger: Trigger,
        val actions: List<Action>,
        val enabled: Boolean = true,
        val cooldownMs: Long = 60_000,
        val createdAt: Long = System.currentTimeMillis(),
        val lastTriggered: Long? = null
    )
    
    sealed class Trigger {
        data class Time(val hour: Int, val minute: Int, val repeat: Repeat = Repeat.DAILY) : Trigger()
        data class Context(val condition: String) : Trigger()
        data class WifiConnected(val ssid: String? = null) : Trigger()
        data class BluetoothConnected(val deviceName: String? = null) : Trigger()
        data class BatteryBelow(val level: Int) : Trigger()
        data class AppOpened(val packageName: String) : Trigger()
        data class LocationChange(val type: LocationType) : Trigger()
    }
    
    enum class Repeat { ONCE, DAILY, WEEKLY, WEEKDAYS }
    enum class LocationType { HOME, WORK, OTHER }
    
    sealed class Action {
        data class OpenApp(val packageName: String) : Action()
        data class OpenUrl(val url: String) : Action()
        data class SetVolume(val level: Int) : Action()
        data class ToggleWifi(val on: Boolean) : Action()
        data class ToggleBluetooth(val on: Boolean) : Action()
        data class ToggleFlashlight(val on: Boolean) : Action()
        data class SetBrightness(val level: Int) : Action()
        data class EnableDND(val on: Boolean) : Action()
        data class EnablePowerSave(val on: Boolean) : Action()
        data class Speak(val text: String) : Action()
        data class Notification(val title: String, val message: String) : Action()
        data class RunSkill(val skillId: String, val params: Map<String, String> = emptyMap()) : Action()
        data class HttpRequest(val url: String, val method: String = "GET") : Action()
    }
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences("khossa_automations", Context.MODE_PRIVATE)
        loadAutomations()
        isInit = true
        Logger.d(name, "AutomationEngine initialized with ${_automations.value.size} automations")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        startMonitoring()
        Logger.d(name, "AutomationEngine started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        Logger.d(name, "AutomationEngine stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        // Reset last triggered times
        val updated = _automations.value.map { it.copy(lastTriggered = null) }
        _automations.value = updated
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun setContextAwareness(ca: ContextAwareness) {
        contextAwareness = ca
    }
    
    private fun startMonitoring() {
        scope.launch {
            while (isRun) {
                checkTimeTriggers()
                checkContextTriggers()
                delay(30_000) // Check every 30 seconds
            }
        }
    }
    
    private fun checkTimeTriggers() {
        val now = java.util.Calendar.getInstance()
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(java.util.Calendar.MINUTE)
        val currentDayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)
        
        for (automation in _automations.value) {
            if (!automation.enabled || automation.id !in _enabledAutomations.value) continue
            
            val trigger = automation.trigger
            if (trigger is Trigger.Time) {
                if (trigger.hour == currentHour && trigger.minute == currentMinute) {
                    if (shouldTrigger(automation)) {
                        triggerAutomation(automation)
                    }
                }
            }
        }
    }
    
    private fun checkContextTriggers() {
        val ca = contextAwareness ?: return
        
        for (automation in _automations.value) {
            if (!automation.enabled || automation.id !in _enabledAutomations.value) continue
            
            val trigger = automation.trigger
            when (trigger) {
                is Trigger.Context -> {
                    if (ca.matchesCondition(trigger.condition)) {
                        if (shouldTrigger(automation)) {
                            triggerAutomation(automation)
                        }
                    }
                }
                is Trigger.WifiConnected -> {
                    val state = ca.contextState.value
                    if (state.connectivity.isWifiConnected) {
                        if (trigger.ssid == null || state.connectivity.ssid == trigger.ssid) {
                            if (shouldTrigger(automation)) {
                                triggerAutomation(automation)
                            }
                        }
                    }
                }
                is Trigger.BluetoothConnected -> {
                    val state = ca.contextState.value
                    if (state.connectivity.isBluetoothConnected) {
                        if (shouldTrigger(automation)) {
                            triggerAutomation(automation)
                        }
                    }
                }
                is Trigger.BatteryBelow -> {
                    val state = ca.contextState.value
                    if (state.device.batteryLevel <= trigger.level) {
                        if (shouldTrigger(automation)) {
                            triggerAutomation(automation)
                        }
                    }
                }
                else -> {}
            }
        }
    }
    
    private fun shouldTrigger(automation: Automation): Boolean {
        val lastTriggered = _lastTriggered.value[automation.id] ?: return true
        return System.currentTimeMillis() - lastTriggered > automation.cooldownMs
    }
    
    private fun triggerAutomation(automation: Automation) {
        _lastTriggered.value = _lastTriggered.value + (automation.id to System.currentTimeMillis())
        
        // Update last triggered in automations list
        _automations.value = _automations.value.map {
            if (it.id == automation.id) it.copy(lastTriggered = System.currentTimeMillis()) else it
        }
        
        Logger.i(name, "Triggering automation: ${automation.name}")
        EventBus.publish(EventBus.AutomationTriggeredEvent(automation.id, automation.trigger.toString()))
        
        scope.launch {
            for (action in automation.actions) {
                executeAction(action)
            }
        }
    }
    
    private suspend fun executeAction(action: Action) {
        when (action) {
            is Action.OpenApp -> {
                val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
                intent?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            is Action.Speak -> {
                // Would integrate with VoiceInteractionManager
                Logger.d(name, "Speak: ${action.text}")
            }
            is Action.Notification -> {
                val notification = android.app.Notification.Builder(context, "automation")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(action.title)
                    .setContentText(action.message)
                    .setAutoCancel(true)
                    .build()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(System.currentTimeMillis().toInt(), notification)
            }
            else -> {
                Logger.d(name, "Action executed: ${action.javaClass.simpleName}")
            }
        }
    }
    
    // CRUD Operations
    fun createAutomation(automation: Automation) {
        val current = _automations.value.toMutableList()
        current.add(automation)
        _automations.value = current
        _enabledAutomations.value = _enabledAutomations.value + automation.id
        saveAutomations()
        EventBus.publish(EventBus.ModuleEvent(name, "automation_created", mapOf("id" to automation.id)))
    }
    
    fun updateAutomation(automation: Automation) {
        _automations.value = _automations.value.map { 
            if (it.id == automation.id) automation else it 
        }
        saveAutomations()
    }
    
    fun deleteAutomation(id: String) {
        _automations.value = _automations.value.filter { it.id != id }
        _enabledAutomations.value = _enabledAutomations.value - id
        _lastTriggered.value = _lastTriggered.value - id
        saveAutomations()
        EventBus.publish(EventBus.ModuleEvent(name, "automation_deleted", mapOf("id" to id)))
    }
    
    fun enableAutomation(id: String) {
        _enabledAutomations.value = _enabledAutomations.value + id
        saveAutomations()
    }
    
    fun disableAutomation(id: String) {
        _enabledAutomations.value = _enabledAutomations.value - id
        saveAutomations()
    }
    
    fun getAutomation(id: String): Automation? {
        return _automations.value.find { it.id == id }
    }
    
    // Persistence
    private fun saveAutomations() {
        val jsonArray = JSONArray()
        for (automation in _automations.value) {
            jsonArray.put(automationToJson(automation))
        }
        prefs.edit().putString("automations", jsonArray.toString()).apply()
        prefs.edit().putStringSet("enabled", _enabledAutomations.value).apply()
    }
    
    private fun loadAutomations() {
        val json = prefs.getString("automations", "[]") ?: "[]"
        val enabledSet = prefs.getStringSet("enabled", emptySet()) ?: emptySet()
        
        try {
            val jsonArray = JSONArray(json)
            val list = mutableListOf<Automation>()
            for (i in 0 until jsonArray.length()) {
                jsonToAutomation(jsonArray.getJSONObject(i))?.let { list.add(it) }
            }
            _automations.value = list
            _enabledAutomations.value = enabledSet
        } catch (e: Exception) {
            Logger.e(name, "Error loading automations", e)
            _automations.value = emptyList()
            _enabledAutomations.value = emptySet()
        }
    }
    
    private fun automationToJson(automation: Automation): JSONObject {
        val actionsArray = org.json.JSONArray()
        automation.actions.forEach { actionsArray.put(actionToJson(it)) }
        return JSONObject().apply {
            put("id", automation.id)
            put("name", automation.name)
            put("description", automation.description)
            put("trigger", triggerToJson(automation.trigger))
            put("actions", actionsArray)
            put("enabled", automation.enabled)
            put("cooldownMs", automation.cooldownMs)
            put("createdAt", automation.createdAt)
        }
    }
    
    private fun triggerToJson(trigger: Trigger): JSONObject {
        return JSONObject().apply {
            when (trigger) {
                is Trigger.Time -> {
                    put("type", "time")
                    put("hour", trigger.hour)
                    put("minute", trigger.minute)
                    put("repeat", trigger.repeat.name)
                }
                is Trigger.Context -> {
                    put("type", "context")
                    put("condition", trigger.condition)
                }
                is Trigger.WifiConnected -> {
                    put("type", "wifi")
                    put("ssid", trigger.ssid ?: "")
                }
                is Trigger.BluetoothConnected -> {
                    put("type", "bluetooth")
                    put("deviceName", trigger.deviceName ?: "")
                }
                is Trigger.BatteryBelow -> {
                    put("type", "battery")
                    put("level", trigger.level)
                }
                is Trigger.AppOpened -> {
                    put("type", "app")
                    put("packageName", trigger.packageName)
                }
                is Trigger.LocationChange -> {
                    put("type", "location")
                    put("locationType", trigger.type.name)
                }
            }
        }
    }
    
    private fun actionToJson(action: Action): JSONObject {
        return JSONObject().apply {
            when (action) {
                is Action.OpenApp -> { put("type", "openApp"); put("packageName", action.packageName) }
                is Action.OpenUrl -> { put("type", "openUrl"); put("url", action.url) }
                is Action.SetVolume -> { put("type", "setVolume"); put("level", action.level) }
                is Action.ToggleWifi -> { put("type", "toggleWifi"); put("on", action.on) }
                is Action.ToggleBluetooth -> { put("type", "toggleBluetooth"); put("on", action.on) }
                is Action.ToggleFlashlight -> { put("type", "toggleFlashlight"); put("on", action.on) }
                is Action.SetBrightness -> { put("type", "setBrightness"); put("level", action.level) }
                is Action.EnableDND -> { put("type", "enableDND"); put("on", action.on) }
                is Action.EnablePowerSave -> { put("type", "enablePowerSave"); put("on", action.on) }
                is Action.Speak -> { put("type", "speak"); put("text", action.text) }
                is Action.Notification -> { put("type", "notification"); put("title", action.title); put("message", action.message) }
                is Action.RunSkill -> { put("type", "runSkill"); put("skillId", action.skillId) }
                is Action.HttpRequest -> { put("type", "http"); put("url", action.url); put("method", action.method) }
            }
        }
    }
    
    private fun jsonToAutomation(json: JSONObject): Automation? {
        return try {
            Automation(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                trigger = jsonToTrigger(json.getJSONObject("trigger")),
                actions = json.getJSONArray("actions").let { arr ->
                    (0 until arr.length()).mapNotNull { jsonToAction(arr.getJSONObject(it)) }
                },
                enabled = json.optBoolean("enabled", true),
                cooldownMs = json.optLong("cooldownMs", 60_000),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun jsonToTrigger(json: JSONObject): Trigger {
        return when (json.getString("type")) {
            "time" -> Trigger.Time(json.getInt("hour"), json.getInt("minute"), Repeat.valueOf(json.optString("repeat", "DAILY")))
            "context" -> Trigger.Context(json.getString("condition"))
            "wifi" -> Trigger.WifiConnected(json.optString("ssid", "").takeIf { it.isNotEmpty() })
            "bluetooth" -> Trigger.BluetoothConnected(json.optString("deviceName", "").takeIf { it.isNotEmpty() })
            "battery" -> Trigger.BatteryBelow(json.getInt("level"))
            "app" -> Trigger.AppOpened(json.getString("packageName"))
            "location" -> Trigger.LocationChange(LocationType.valueOf(json.optString("locationType", "OTHER")))
            else -> Trigger.Context("unknown")
        }
    }
    
    private fun jsonToAction(json: JSONObject): Action? {
        return try {
            when (json.getString("type")) {
                "openApp" -> Action.OpenApp(json.getString("packageName"))
                "openUrl" -> Action.OpenUrl(json.getString("url"))
                "setVolume" -> Action.SetVolume(json.getInt("level"))
                "toggleWifi" -> Action.ToggleWifi(json.getBoolean("on"))
                "toggleBluetooth" -> Action.ToggleBluetooth(json.getBoolean("on"))
                "toggleFlashlight" -> Action.ToggleFlashlight(json.getBoolean("on"))
                "setBrightness" -> Action.SetBrightness(json.getInt("level"))
                "enableDND" -> Action.EnableDND(json.getBoolean("on"))
                "enablePowerSave" -> Action.EnablePowerSave(json.getBoolean("on"))
                "speak" -> Action.Speak(json.getString("text"))
                "notification" -> Action.Notification(json.getString("title"), json.getString("message"))
                "runSkill" -> Action.RunSkill(json.getString("skillId"))
                "http" -> Action.HttpRequest(json.getString("url"), json.optString("method", "GET"))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
