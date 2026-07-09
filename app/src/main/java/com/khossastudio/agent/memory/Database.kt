package com.khossastudio.agent.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserPreference::class, FrequentLocation::class, Routine::class,
        FavoriteApp::class, CommandHistory::class, Goal::class,
        Habit::class, BluetoothDevice::class, KnownWifi::class,
        Reminder::class, ShortTermMemory::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun preferenceDao(): PreferenceDao
    abstract fun locationDao(): LocationDao
    abstract fun routineDao(): RoutineDao
    abstract fun favoriteAppDao(): FavoriteAppDao
    abstract fun commandDao(): CommandDao
    abstract fun goalDao(): GoalDao
    abstract fun habitDao(): HabitDao
    abstract fun bluetoothDao(): BluetoothDao
    abstract fun wifiDao(): WifiDao
    abstract fun reminderDao(): ReminderDao
    abstract fun shortTermDao(): ShortTermDao

    companion object {
        @Volatile private var INSTANCE: AgentDatabase? = null
        fun getInstance(ctx: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(ctx.applicationContext, AgentDatabase::class.java, "khossa_memory")
                    .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
