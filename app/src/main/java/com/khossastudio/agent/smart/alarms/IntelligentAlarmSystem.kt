package com.khossastudio.agent.smart.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

class IntelligentAlarmSystem : KhossaModule {
    override val name = "intelligent_alarms"
    override val version = "1.0.0"
    override val dependencies: List<String> = listOf("core", "voice")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    private val _alarms = MutableStateFlow<List<SmartAlarm>>(emptyList())
    val alarms: StateFlow<List<SmartAlarm>> = _alarms.asStateFlow()
    
    private val _activeAlarm = MutableStateFlow<SmartAlarm?>(null)
    val activeAlarm: StateFlow<SmartAlarm?> = _activeAlarm.asStateFlow()
    
    private val alarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    
    data class SmartAlarm(
        val id: String = UUID.randomUUID().toString(),
        val hour: Int,
        val minute: Int,
        val label: String = "",
        val days: List<Int> = emptyList(), // 1=Sun, 2=Mon... 7=Sat, empty=daily
        val enabled: Boolean = true,
        val vibrate: Boolean = true,
        val soundUri: String? = null,
        val routine: AlarmRoutine? = null,
        val repeatType: RepeatType = RepeatType.ONCE
    )
    
    enum class RepeatType { ONCE, DAILY, WEEKDAYS, WEEKENDS, CUSTOM }
    
    data class AlarmRoutine(
        val speakAgenda: Boolean = false,
        val speakWeather: Boolean = false,
        val speakBattery: Boolean = false,
        val speakTasks: Boolean = false,
        val customMessage: String? = null,
        val openApp: String? = null,
        val actions: List<String> = emptyList()
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        initTts()
        isInit = true
        Logger.d(name, "IntelligentAlarmSystem initialized")
    }
    
    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("pt", "BR")
                tts?.setSpeechRate(1.1f)
            }
        }
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "IntelligentAlarmSystem started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        _alarms.value.forEach { cancelAlarm(it) }
        _alarms.value = emptyList()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun createAlarm(
        hour: Int,
        minute: Int,
        label: String = "",
        days: List<Int> = emptyList(),
        routine: AlarmRoutine? = null
    ): SmartAlarm {
        val alarm = SmartAlarm(
            hour = hour,
            minute = minute,
            label = label,
            days = days,
            routine = routine,
            repeatType = when {
                days.isEmpty() -> RepeatType.ONCE
                days.containsAll(listOf(2,3,4,5,6)) -> RepeatType.WEEKDAYS
                days.containsAll(listOf(1,7)) -> RepeatType.WEEKENDS
                else -> RepeatType.CUSTOM
            }
        )
        _alarms.value = _alarms.value + alarm
        scheduleAlarm(alarm)
        Logger.d(name, "Alarm created: ${alarm.hour}:${alarm.minute}")
        return alarm
    }
    
    fun updateAlarm(id: String, updates: (SmartAlarm) -> SmartAlarm) {
        _alarms.value = _alarms.value.map { alarm ->
            if (alarm.id == id) {
                val updated = updates(alarm)
                cancelAlarm(alarm)
                if (updated.enabled) scheduleAlarm(updated)
                updated
            } else alarm
        }
    }
    
    fun deleteAlarm(id: String) {
        _alarms.value.find { it.id == id }?.let { cancelAlarm(it) }
        _alarms.value = _alarms.value.filter { it.id != id }
    }
    
    fun toggleAlarm(id: String) {
        updateAlarm(id) { it.copy(enabled = !it.enabled) }
    }
    
    fun snoozeAlarm(id: String, minutes: Int = 5) {
        val alarm = _alarms.value.find { it.id == id } ?: return
        val snoozed = alarm.copy(hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        // Add minutes to current time
        val cal = Calendar.getInstance()
        cal.add(Calendar.MINUTE, minutes)
        // Schedule new alarm with same ID
        scheduleAlarmAt(snoozed, cal.timeInMillis)
        Logger.d(name, "Alarm snoozed for $minutes minutes")
    }
    
    private fun scheduleAlarm(alarm: SmartAlarm) {
        if (!alarm.enabled) return
        
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            // If time already passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
            
            // Handle repeat days
            if (alarm.days.isNotEmpty()) {
                while (alarm.days.none { day ->
                    val dayOfWeek = get(Calendar.DAY_OF_WEEK)
                    dayOfWeek == day || (day == 1 && dayOfWeek == Calendar.SUNDAY) || (day == 7 && dayOfWeek == Calendar.SATURDAY)
                }) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        
        scheduleAlarmAt(alarm, cal.timeInMillis)
    }
    
    private fun scheduleAlarmAt(alarm: SmartAlarm, time: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarm.id)
            putExtra("alarm_label", alarm.label)
        }
        
        val pending = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }
    
    private fun cancelAlarm(alarm: SmartAlarm) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let { alarmManager.cancel(it) }
    }
    
    fun triggerAlarm(alarmId: String) {
        val alarm = _alarms.value.find { it.id == alarmId } ?: return
        _activeAlarm.value = alarm
        
        // Vibrate
        if (alarm.vibrate) {
            startVibration()
        }
        
        // Build greeting message
        val greeting = buildGreetingMessage(alarm)
        
        // Speak greeting
        speak(greeting)
        
        // Publish event
        Logger.d(name, "Alarm triggered: $alarmId")
    }
    
    private fun buildGreetingMessage(alarm: SmartAlarm): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        val greeting = when {
            hour < 6 -> "Boa madrugada"
            hour < 12 -> "Bom dia"
            hour < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
        
        return buildString {
            append("$greeting. ")
            append("São ${alarm.hour} horas")
            if (alarm.label.isNotEmpty()) {
                append(". ${alarm.label}")
            }
            if (alarm.routine != null) {
                append(". ")
                if (alarm.routine.speakAgenda) {
                    append("Você tem compromissos programados para hoje.")
                }
                if (alarm.routine.speakBattery) {
                    append("Bateria está em nível moderado.")
                }
            }
        }
    }
    
    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm_greeting")
        }
    }
    
    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }
    
    fun dismissAlarm(alarmId: String) {
        _activeAlarm.value = null
        stopVibration()
        
        // Handle repeat
        val alarm = _alarms.value.find { it.id == alarmId }
        if (alarm != null && alarm.repeatType != RepeatType.ONCE) {
            // Reschedule for next occurrence
            scheduleAlarm(alarm)
        }
    }
    
    private fun stopVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.cancel()
    }
    
    fun testAlarm(alarm: SmartAlarm) {
        triggerAlarm(alarm.id)
    }
    
    fun speakCustomMessage(message: String) {
        speak(message)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        
        // The actual alarm triggering would be handled by the service
        // This is a placeholder for the broadcast receiver
        Logger.d("AlarmReceiver", "Alarm triggered: $alarmId")
    }
}
