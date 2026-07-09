package com.khossastudio.agent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface PreferenceDao {
    @Query("SELECT * FROM preferences") fun getAll(): Flow<List<UserPreference>>
    @Query("SELECT * FROM preferences WHERE `key` = :key") suspend fun get(key: String): UserPreference?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(p: UserPreference)
    @Query("DELETE FROM preferences WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM preferences") suspend fun deleteAll()
}

@Dao interface LocationDao {
    @Query("SELECT * FROM frequent_locations ORDER BY visitCount DESC") fun getAll(): Flow<List<FrequentLocation>>
    @Query("SELECT * FROM frequent_locations ORDER BY visitCount DESC LIMIT :n") fun getTop(n: Int): Flow<List<FrequentLocation>>
    @Insert suspend fun insert(l: FrequentLocation)
    @Query("UPDATE frequent_locations SET visitCount = visitCount + 1 WHERE id = :id") suspend fun increment(id: Long)
}

@Dao interface RoutineDao {
    @Query("SELECT * FROM routines WHERE enabled = 1") fun getEnabled(): Flow<List<Routine>>
    @Query("SELECT * FROM routines") fun getAll(): Flow<List<Routine>>
    @Insert suspend fun insert(r: Routine): Long
    @Query("UPDATE routines SET lastTriggered = :t WHERE id = :id") suspend fun updateTriggered(id: Long, t: Long)
}

@Dao interface FavoriteAppDao {
    @Query("SELECT * FROM favorite_apps ORDER BY launchCount DESC") fun getAll(): Flow<List<FavoriteApp>>
    @Query("SELECT * FROM favorite_apps ORDER BY launchCount DESC LIMIT :n") fun getTop(n: Int): Flow<List<FavoriteApp>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(a: FavoriteApp)
    @Query("UPDATE favorite_apps SET launchCount = launchCount + 1 WHERE packageName = :pkg") suspend fun increment(pkg: String)
}

@Dao interface CommandDao {
    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT :n") fun getRecent(n: Int = 100): Flow<List<CommandHistory>>
    @Query("SELECT * FROM command_history WHERE command LIKE '%' || :q || '%'") fun search(q: String): Flow<List<CommandHistory>>
    @Query("SELECT AVG(CAST(success AS FLOAT)) FROM command_history WHERE timestamp > :since") suspend fun successRate(since: Long): Float?
    @Insert suspend fun insert(c: CommandHistory)
    @Query("DELETE FROM command_history WHERE timestamp < :before") suspend fun deleteOld(before: Long)
}

@Dao interface GoalDao {
    @Query("SELECT * FROM goals WHERE completed = 0") fun getActive(): Flow<List<Goal>>
    @Insert suspend fun insert(g: Goal): Long
    @Query("UPDATE goals SET completed = 1, completedAt = :t WHERE id = :id") suspend fun complete(id: Long, t: Long)
}

@Dao interface HabitDao {
    @Query("SELECT * FROM habits") fun getAll(): Flow<List<Habit>>
    @Insert suspend fun insert(h: Habit): Long
    @Query("UPDATE habits SET streakCount = streakCount + 1, lastCompleted = :t WHERE id = :id") suspend fun incrementStreak(id: Long, t: Long)
}

@Dao interface BluetoothDao {
    @Query("SELECT * FROM bluetooth_devices") fun getAll(): Flow<List<BluetoothDevice>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(d: BluetoothDevice)
}

@Dao interface WifiDao {
    @Query("SELECT * FROM known_wifi") fun getAll(): Flow<List<KnownWifi>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(w: KnownWifi)
}

@Dao interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY time ASC") fun getEnabled(): Flow<List<Reminder>>
    @Insert suspend fun insert(r: Reminder): Long
    @Query("UPDATE reminders SET enabled = :e WHERE id = :id") suspend fun setEnabled(id: Long, e: Boolean)
}

@Dao interface ShortTermDao {
    @Query("SELECT * FROM short_term_memory WHERE expiresAt > :now") fun getActive(now: Long = System.currentTimeMillis()): Flow<List<ShortTermMemory>>
    @Query("SELECT * FROM short_term_memory WHERE `key` = :k AND expiresAt > :now") suspend fun get(k: String, now: Long = System.currentTimeMillis()): ShortTermMemory?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(m: ShortTermMemory)
    @Query("DELETE FROM short_term_memory WHERE expiresAt < :now") suspend fun deleteExpired(now: Long = System.currentTimeMillis())
    @Query("DELETE FROM short_term_memory WHERE `key` = :k") suspend fun delete(k: String)
    @Query("DELETE FROM short_term_memory") suspend fun deleteAll()
}
