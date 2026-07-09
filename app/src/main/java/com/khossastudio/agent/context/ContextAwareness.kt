package com.khossastudio.agent.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Context Awareness - Tracks device and environmental context.
 * Considers: time, location, movement, screen state, battery, connectivity, etc.
 */
class ContextAwareness : KhossaModule, SensorEventListener {
    override val name = "context"
    override val version = "1.0.0"
    override val dependencies = listOf("core", "capabilities")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var sensorManager: SensorManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var locationManager: LocationManager
    
    private val _contextState = MutableStateFlow<ContextState>(ContextState())
    val contextState: StateFlow<ContextState> = _contextState.asStateFlow()
    
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var connectivityReceiver: BroadcastReceiver? = null
    
    data class ContextState(
        val time: TimeContext = TimeContext(),
        val location: LocationContext = LocationContext(),
        val motion: MotionContext = MotionContext(),
        val device: DeviceContext = DeviceContext(),
        val connectivity: ConnectivityContext = ConnectivityContext(),
        val calendar: CalendarContext = CalendarContext(),
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class TimeContext(
        val hour: Int = 0,
        val minute: Int = 0,
        val dayOfWeek: Int = 0,
        val dayOfMonth: Int = 0,
        val month: Int = 0,
        val isWeekend: Boolean = false,
        val period: TimePeriod = TimePeriod.UNKNOWN
    )
    
    enum class TimePeriod {
        MORNING,     // 6-12
        AFTERNOON,   // 12-18
        EVENING,     // 18-22
        NIGHT,       // 22-6
        UNKNOWN
    }
    
    data class LocationContext(
        val isAtHome: Boolean = false,
        val isAtWork: Boolean = false,
        val isMoving: Boolean = false,
        val speed: Float = 0f,
        val accuracy: Float = 0f
    )
    
    data class MotionContext(
        val activity: Activity = Activity.STILL,
        val confidence: Float = 0f,
        val isInVehicle: Boolean = false,
        val isWalking: Boolean = false,
        val isOnFoot: Boolean = false
    )
    
    enum class Activity { STILL, WALKING, RUNNING, IN_VEHICLE, ON_BICYCLE, UNKNOWN }
    
    data class DeviceContext(
        val isScreenOn: Boolean = false,
        val isCharging: Boolean = false,
        val batteryLevel: Int = 100,
        val isPowerSaveMode: Boolean = false,
        val isDarkMode: Boolean = false,
        val language: String = "pt",
        val ringerMode: RingerMode = RingerMode.NORMAL
    )
    
    enum class RingerMode { NORMAL, VIBRATE, SILENT }
    
    data class ConnectivityContext(
        val isWifiConnected: Boolean = false,
        val isMobileConnected: Boolean = false,
        val isBluetoothConnected: Boolean = false,
        val isNfcEnabled: Boolean = false,
        val ssid: String? = null
    )
    
    data class CalendarContext(
        val nextEvent: String? = null,
        val nextEventTime: Long? = null,
        val isInMeeting: Boolean = false
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        updateTimeContext()
        updateDeviceContext()
        updateConnectivityContext()
        
        isInit = true
        Logger.d(name, "ContextAwareness initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        
        // Register receivers
        registerScreenReceiver()
        registerBatteryReceiver()
        registerConnectivityReceiver()
        
        // Start sensor listeners
        startSensorListeners()
        
        // Start periodic updates
        scope.launch {
            while (isRun) {
                updateTimeContext()
                updateDeviceContext()
                publishContextChange()
                delay(60_000) // Update every minute
            }
        }
        
        Logger.d(name, "ContextAwareness started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        
        unregisterReceivers()
        stopSensorListeners()
        
        Logger.d(name, "ContextAwareness stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        updateTimeContext()
        updateDeviceContext()
        updateConnectivityContext()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val isScreenOn = intent.action == Intent.ACTION_SCREEN_ON
                _contextState.value = _contextState.value.copy(
                    device = _contextState.value.device.copy(isScreenOn = isScreenOn)
                )
                EventBus.publish(EventBus.ContextChangedEvent(mapOf("screenOn" to isScreenOn)))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
    }
    
    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                val isPowerSave = powerManager.isPowerSaveMode
                
                _contextState.value = _contextState.value.copy(
                    device = _contextState.value.device.copy(
                        batteryLevel = level * 100 / scale,
                        isCharging = isCharging,
                        isPowerSaveMode = isPowerSave
                    )
                )
            }
        }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    
    private fun registerConnectivityReceiver() {
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                updateConnectivityContext()
            }
        }
        context.registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }
    
    private fun unregisterReceivers() {
        screenReceiver?.let { context.unregisterReceiver(it) }
        batteryReceiver?.let { context.unregisterReceiver(it) }
        connectivityReceiver?.let { context.unregisterReceiver(it) }
    }
    
    private fun startSensorListeners() {
        // Accelerometer for motion detection
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        // Step counter for activity detection
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }
    
    private fun stopSensorListeners() {
        sensorManager.unregisterListener(this)
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> processAccelerometer(event)
            Sensor.TYPE_STEP_COUNTER -> processStepCounter(event)
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun processAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)
        
        // Simple activity detection based on acceleration variance
        val isMoving = magnitude < 9.5f || magnitude > 10.5f
        val activity = when {
            magnitude < 9.5f -> Activity.STILL
            magnitude < 12f -> Activity.WALKING
            else -> Activity.IN_VEHICLE
        }
        
        _contextState.value = _contextState.value.copy(
            motion = _contextState.value.motion.copy(
                activity = activity,
                confidence = 0.7f,
                isWalking = activity == Activity.WALKING,
                isInVehicle = activity == Activity.IN_VEHICLE,
                isOnFoot = activity == Activity.WALKING || activity == Activity.STILL
            )
        )
    }
    
    private fun processStepCounter(event: SensorEvent) {
        // Step detection can be used to refine activity detection
    }
    
    private fun updateTimeContext() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH)
        
        val period = when (hour) {
            in 6..11 -> TimePeriod.MORNING
            in 12..17 -> TimePeriod.AFTERNOON
            in 18..21 -> TimePeriod.EVENING
            else -> TimePeriod.NIGHT
        }
        
        _contextState.value = _contextState.value.copy(
            time = TimeContext(
                hour = hour,
                minute = minute,
                dayOfWeek = dayOfWeek,
                dayOfMonth = dayOfMonth,
                month = month,
                isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY,
                period = period
            )
        )
    }
    
    private fun updateDeviceContext() {
        val ringerMode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            else -> RingerMode.NORMAL
        }
        
        _contextState.value = _contextState.value.copy(
            device = _contextState.value.device.copy(
                isScreenOn = powerManager.isInteractive,
                isPowerSaveMode = powerManager.isPowerSaveMode,
                ringerMode = ringerMode
            )
        )
    }
    
    private fun updateConnectivityContext() {
        val network = connectivityManager.activeNetwork
        val caps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMobile = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isBluetooth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true
        
        _contextState.value = _contextState.value.copy(
            connectivity = ConnectivityContext(
                isWifiConnected = isWifi,
                isMobileConnected = isMobile,
                isBluetoothConnected = isBluetooth,
                ssid = if (isWifi) wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") else null
            )
        )
    }
    
    private fun publishContextChange() {
        _contextState.value = _contextState.value.copy(timestamp = System.currentTimeMillis())
        EventBus.publish(EventBus.ContextChangedEvent(_contextState.value.let {
            mapOf(
                "time" to it.time.hour,
                "period" to it.time.period.name,
                "activity" to it.motion.activity.name,
                "battery" to it.device.batteryLevel,
                "screenOn" to it.device.isScreenOn,
                "wifi" to it.connectivity.isWifiConnected
            )
        }))
    }
    
    /**
     * Get context summary for AI prompts.
     */
    fun getContextSummary(): String {
        val ctx = _contextState.value
        return buildString {
            append("Context: ")
            append("${ctx.time.period.name.lowercase()}, ")
            append("battery ${ctx.device.batteryLevel}%, ")
            if (ctx.device.isCharging) append("charging, ")
            if (ctx.connectivity.isWifiConnected) append("wifi: ${ctx.connectivity.ssid ?: "connected"}, ")
            if (ctx.connectivity.isBluetoothConnected) append("bluetooth connected, ")
            append("screen ${if (ctx.device.isScreenOn) "on" else "off"}, ")
            append("activity: ${ctx.motion.activity.name.lowercase()}")
        }
    }
    
    /**
     * Check if a specific context matches.
     */
    fun matchesCondition(condition: String): Boolean {
        val ctx = _contextState.value
        return when (condition.lowercase()) {
            "at_home" -> ctx.location.isAtHome
            "at_work" -> ctx.location.isAtWork
            "moving" -> ctx.motion.isInVehicle || ctx.motion.isWalking
            "still" -> ctx.motion.activity == Activity.STILL
            "charging" -> ctx.device.isCharging
            "low_battery" -> ctx.device.batteryLevel < 20
            "wifi" -> ctx.connectivity.isWifiConnected
            "bluetooth" -> ctx.connectivity.isBluetoothConnected
            "screen_on" -> ctx.device.isScreenOn
            "screen_off" -> !ctx.device.isScreenOn
            "morning" -> ctx.time.period == TimePeriod.MORNING
            "afternoon" -> ctx.time.period == TimePeriod.AFTERNOON
            "evening" -> ctx.time.period == TimePeriod.EVENING
            "night" -> ctx.time.period == TimePeriod.NIGHT
            "weekend" -> ctx.time.isWeekend
            "weekday" -> !ctx.time.isWeekend
            else -> false
        }
    }
}
