package com.khossastudio.agent.memory

import android.content.Context
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MemoryManager : KhossaModule {
    override val name = "memory"
    override val version = "1.0.0"
    override val dependencies: List<String> = listOf("core")
    
    private lateinit var ctx: Context
    private var isInit = false
    private var isRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AgentDatabase
    
    override suspend fun initialize(context: Context): Result<Unit> = runCatching {
        ctx = context.applicationContext
        db = AgentDatabase.getInstance(ctx)
        cleanExpired()
        isInit = true
        Logger.d(name, "MemoryManager initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        scope.launch { while (isRun) { cleanExpired(); kotlinx.coroutines.delay(60000) } }
        Logger.d(name, "MemoryManager started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching { isRun = false }
    override suspend fun reset(): Result<Unit> = runCatching { db.shortTermDao().deleteAll() }
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    private fun cleanExpired() { scope.launch { db.shortTermDao().deleteExpired() } }
    
    // Preferences
    suspend fun getPref(key: String): String? = db.preferenceDao().get(key)?.value
    suspend fun setPref(key: String, value: String) = db.preferenceDao().insert(UserPreference(key, value))
    fun observePrefs(): Flow<List<UserPreference>> = db.preferenceDao().getAll()
    
    // Locations
    fun observeLocations(): Flow<List<FrequentLocation>> = db.locationDao().getTop(5)
    suspend fun addLocation(name: String, lat: Double, lng: Double, address: String? = null) {
        db.locationDao().insert(FrequentLocation(name = name, address = address, latitude = lat, longitude = lng))
    }
    
    // Routines
    fun observeRoutines(): Flow<List<Routine>> = db.routineDao().getEnabled()
    suspend fun createRoutine(name: String, trigger: String, triggerValue: String?, actions: String, description: String? = null): Long {
        return db.routineDao().insert(Routine(name = name, description = description, trigger = trigger, triggerValue = triggerValue, actions = actions))
    }
    
    // Apps
    fun observeApps(): Flow<List<FavoriteApp>> = db.favoriteAppDao().getTop(10)
    suspend fun recordApp(pkg: String, appName: String) { db.favoriteAppDao().insert(FavoriteApp(pkg, appName)) }
    suspend fun mostUsedApp(): FavoriteApp? = db.favoriteAppDao().getTop(1).first().firstOrNull()
    
    // Commands
    fun observeCommands(n: Int = 50): Flow<List<CommandHistory>> = db.commandDao().getRecent(n)
    suspend fun recordCommand(cmd: String, offline: Boolean, ok: Boolean, response: String?, intent: String?) {
        db.commandDao().insert(CommandHistory(cmd, offline, ok, response, intent))
    }
    suspend fun successRate(): Float = db.commandDao().successRate(System.currentTimeMillis() - 86400000) ?: 0f
    suspend fun searchCommands(q: String): List<CommandHistory> = db.commandDao().search(q).first()
    
    // Goals
    fun observeGoals(): Flow<List<Goal>> = db.goalDao().getActive()
    suspend fun createGoal(title: String, desc: String? = null, due: Long? = null): Long {
        return db.goalDao().insert(Goal(title = title, description = desc, dueDate = due))
    }
    suspend fun completeGoal(id: Long) = db.goalDao().complete(id, System.currentTimeMillis())
    
    // Habits
    fun observeHabits(): Flow<List<Habit>> = db.habitDao().getAll()
    suspend fun createHabit(name: String, trigger: String, triggerTime: String? = null): Long {
        return db.habitDao().insert(Habit(name = name, trigger = trigger, triggerTime = triggerTime))
    }
    suspend fun completeHabit(id: Long) = db.habitDao().incrementStreak(id, System.currentTimeMillis())
    
    // Bluetooth
    fun observeBluetooth(): Flow<List<BluetoothDevice>> = db.bluetoothDao().getAll()
    suspend fun recordBluetooth(mac: String, name: String, type: String) {
        db.bluetoothDao().insert(BluetoothDevice(mac, name, type))
    }
    
    // WiFi
    fun observeWifi(): Flow<List<KnownWifi>> = db.wifiDao().getAll()
    suspend fun recordWifi(ssid: String) = db.wifiDao().insert(KnownWifi(ssid, "WPA2"))
    
    // Reminders
    fun observeReminders(): Flow<List<Reminder>> = db.reminderDao().getEnabled()
    suspend fun createReminder(title: String, time: Long, desc: String? = null): Long {
        return db.reminderDao().insert(Reminder(0, title, desc, time, null, true, System.currentTimeMillis()))
    }
    suspend fun toggleReminder(id: Long, enabled: Boolean) = db.reminderDao().setEnabled(id, enabled)
    
    // Short-term memory
    suspend fun remember(key: String, value: String, ttl: Long = 300000) {
        db.shortTermDao().insert(ShortTermMemory(key = key, value = value, context = null, expiresAt = System.currentTimeMillis() + ttl))
    }
    suspend fun recall(key: String): String? = db.shortTermDao().get(key)?.value
    suspend fun forget(key: String) = db.shortTermDao().delete(key)
    
    // AI Context
    suspend fun contextForAI(): Map<String, Any> {
        return mapOf(
            "recent" to db.commandDao().getRecent(5).first().map { it.command },
            "top_apps" to db.favoriteAppDao().getTop(3).first().map { it.packageName },
            "active_goals" to db.goalDao().getActive().first().size,
            "habits" to db.habitDao().getAll().first().map { it.name },
            "success_rate" to successRate()
        )
    }
    
    // Compatibility
    suspend fun allContacts(): List<ContactMemory> = emptyList()
    suspend fun allPreferences(): List<UserPreference> = db.preferenceDao().getAll().first()
    suspend fun rememberContact(name: String, phone: String?, relationship: String?) {}
    suspend fun rememberPreference(key: String, value: String) { setPref(key, value) }
    suspend fun recallPreference(key: String): String? = getPref(key)
    
    // Logging
    fun logTask(message: String) { Logger.d(name, message) }
}
