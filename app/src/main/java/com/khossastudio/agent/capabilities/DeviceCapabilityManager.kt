package com.khossastudio.agent.capabilities

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceCapabilityManager : KhossaModule {
    override val name = "capabilities"
    override val version = "1.0.0"
    override val dependencies: List<String> = emptyList()
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    
    private val _capabilities = MutableStateFlow(DeviceCapabilities())
    val capabilities: StateFlow<DeviceCapabilities> = _capabilities.asStateFlow()
    
    private val _batteryInfo = MutableStateFlow(BatteryInfo())
    val batteryInfo: StateFlow<BatteryInfo> = _batteryInfo.asStateFlow()
    
    data class DeviceCapabilities(
        val bluetoothAvailable: Boolean = false,
        val bluetoothEnabled: Boolean = false,
        val wifiAvailable: Boolean = false,
        val wifiEnabled: Boolean = false,
        val wifiConnected: Boolean = false,
        val nfcAvailable: Boolean = false,
        val locationEnabled: Boolean = false,
        val sensors: List<SensorInfo> = emptyList(),
        val cameraCount: Int = 0,
        val hasFlash: Boolean = false,
        val vibrationAvailable: Boolean = false
    )
    
    data class SensorInfo(val name: String, val typeName: String, val vendor: String)
    
    data class BatteryInfo(
        val level: Int = 0,
        val isCharging: Boolean = false,
        val chargingType: String = "none",
        val temperature: Float = 0f
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        detectAll()
        isInit = true
        Logger.d(name, "DeviceCapabilityManager initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        refreshDynamic()
        Logger.d(name, "DeviceCapabilityManager started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        detectAll()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun detectAll() {
        _capabilities.value = DeviceCapabilities(
            bluetoothAvailable = BluetoothAdapter.getDefaultAdapter() != null,
            bluetoothEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true,
            wifiAvailable = context.getSystemService(Context.WIFI_SERVICE) != null,
            wifiEnabled = (context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.isWifiEnabled == true,
            nfcAvailable = NfcAdapter.getDefaultAdapter(context) != null,
            locationEnabled = (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true,
            sensors = detectSensors(),
            cameraCount = detectCameras(),
            hasFlash = detectFlash(),
            vibrationAvailable = detectVibration()
        )
        _batteryInfo.value = detectBattery()
        EventBus.publish(EventBus.DeviceStateChangedEvent(mapOf("capabilities" to "detected")))
    }
    
    fun refreshDynamic() {
        _batteryInfo.value = detectBattery()
    }
    
    private fun detectSensors(): List<SensorInfo> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return sm.getSensorList(Sensor.TYPE_ALL).map { s ->
            SensorInfo(s.name, getSensorTypeName(s.type), s.vendor)
        }
    }
    
    private fun getSensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Acelerometro"
        Sensor.TYPE_GYROSCOPE -> "Giroscopio"
        Sensor.TYPE_LIGHT -> "Sensor de luz"
        Sensor.TYPE_PROXIMITY -> "Proximidade"
        Sensor.TYPE_PRESSURE -> "Barometro"
        Sensor.TYPE_STEP_COUNTER -> "Contador passos"
        else -> "Sensor #$type"
    }
    
    private fun detectCameras(): Int {
        return try {
            (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.cameraIdList?.size ?: 0
        } catch (e: Exception) { 0 }
    }
    
    private fun detectFlash(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cm?.cameraIdList?.any { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } == true
        } catch (e: Exception) { false }
    }
    
    private fun detectVibration(): Boolean {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)?.javaClass?.getMethod("getDefaultVibrator")?.invoke(context)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE)
            }
            vibrator?.javaClass?.getMethod("hasVibrator")?.invoke(vibrator) == true
        } catch (e: Exception) { false }
    }
    
    private fun detectBattery(): BatteryInfo {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val chargingType = when {
                status == BatteryManager.BATTERY_STATUS_CHARGING -> when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                    else -> "Carregando"
                }
                status == BatteryManager.BATTERY_STATUS_FULL -> "Cheia"
                else -> "none"
            }
            BatteryInfo(level * 100 / scale, status == BatteryManager.BATTERY_STATUS_CHARGING, chargingType, temp)
        } catch (e: Exception) { BatteryInfo() }
    }
    
    fun hasCapability(cap: String): Boolean = when (cap) {
        "bluetooth" -> _capabilities.value.bluetoothAvailable
        "wifi" -> _capabilities.value.wifiAvailable
        "nfc" -> _capabilities.value.nfcAvailable
        "location" -> _capabilities.value.locationEnabled
        "camera" -> _capabilities.value.cameraCount > 0
        "vibration" -> _capabilities.value.vibrationAvailable
        else -> false
    }
}
