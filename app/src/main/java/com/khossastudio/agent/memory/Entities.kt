package com.khossastudio.agent.memory

import androidx.room.*

@Entity(tableName = "preferences")
data class UserPreference(@PrimaryKey val key: String, val value: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "frequent_locations")
data class FrequentLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val address: String?, val latitude: Double, val longitude: Double,
    val visitCount: Int = 1, val lastVisit: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val description: String?, val trigger: String, val triggerValue: String?,
    val actions: String, val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(), val lastTriggered: Long? = null
)

@Entity(tableName = "favorite_apps")
data class FavoriteApp(
    @PrimaryKey val packageName: String,
    val appName: String, val launchCount: Int = 1, val lastLaunch: Long = System.currentTimeMillis()
)

@Entity(tableName = "command_history")
data class CommandHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String, val isOffline: Boolean, val success: Boolean,
    val response: String?, val intent: String?, val timestamp: Long = 0L
) {
    constructor(cmd: String, offline: Boolean, ok: Boolean, resp: String?, intnt: String?) : 
        this(0, cmd, offline, ok, resp, intnt, System.currentTimeMillis())
}

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val description: String?, val dueDate: Long?,
    val completed: Boolean = false, val createdAt: Long = 0L, val completedAt: Long? = null
) {
    constructor(t: String, d: String?, dd: Long?) : this(0, t, d, dd, false, System.currentTimeMillis(), null)
}

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, val trigger: String, val triggerTime: String?,
    val reminderEnabled: Boolean = false, val streakCount: Int = 0,
    val lastCompleted: Long? = null, val createdAt: Long = 0L
) {
    constructor(n: String, t: String, tt: String?) : this(0, n, t, tt, false, 0, null, System.currentTimeMillis())
}

@Entity(tableName = "bluetooth_devices")
data class BluetoothDevice(
    @PrimaryKey val macAddress: String,
    val name: String, val type: String,
    val firstSeen: Long = System.currentTimeMillis(), val lastConnected: Long? = null, val autoConnect: Boolean = false
)

@Entity(tableName = "known_wifi")
data class KnownWifi(
    @PrimaryKey val ssid: String,
    val securityType: String, val lastConnected: Long? = null, val autoConnect: Boolean = true
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val description: String?, val time: Long, val repeatType: String?,
    val enabled: Boolean = true, val createdAt: Long = 0L
) {
    constructor(t: String, d: String?, tm: Long, r: String?) : this(0, t, d, tm, r, true, System.currentTimeMillis())
}

@Entity(tableName = "short_term_memory")
data class ShortTermMemory(
    @PrimaryKey val key: String,
    val value: String, val context: String?,
    val createdAt: Long = 0L, val expiresAt: Long
) {
    constructor(k: String, v: String, c: String?, e: Long) : this(k, v, c, System.currentTimeMillis(), e)
}

data class ContactMemory(val name: String, val phone: String? = null, val relationship: String? = null)
