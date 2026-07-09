package com.khossastudio.agent.smart.controls

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.hardware.camera2.CameraManager
import com.khossastudio.agent.core.registry.KhossaModule
import com.khossastudio.agent.core.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SmartControlCenter : KhossaModule {
    override val name = "smart_controls"
    override val version = "1.0.0"
    override val dependencies: List<String> = listOf("core", "capabilities")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    
    private val _controlsState = MutableStateFlow(DeviceControlsState())
    val controlsState: StateFlow<DeviceControlsState> = _controlsState.asStateFlow()
    
    private val _controlHistory = MutableStateFlow<List<ControlChange>>(emptyList())
    val controlHistory: StateFlow<List<ControlChange>> = _controlHistory.asStateFlow()
    
    data class DeviceControlsState(
        val wifi: ControlStatus = ControlStatus.UNKNOWN,
        val bluetooth: ControlStatus = ControlStatus.UNKNOWN,
        val location: ControlStatus = ControlStatus.UNKNOWN,
        val nfc: ControlStatus = ControlStatus.UNKNOWN,
        val mobileData: ControlStatus = ControlStatus.UNKNOWN,
        val flashlight: ControlStatus = ControlStatus.OFF,
        val hotspot: ControlStatus = ControlStatus.UNKNOWN,
        val airplaneMode: ControlStatus = ControlStatus.UNKNOWN,
        val doNotDisturb: ControlStatus = ControlStatus.UNKNOWN,
        val powerSave: ControlStatus = ControlStatus.UNKNOWN,
        val screenRotation: ControlStatus = ControlStatus.UNKNOWN,
        val volumeLevel: Int = -1,
        val brightnessLevel: Int = -1
    )
    
    enum class ControlStatus { ON, OFF, UNKNOWN }
    
    data class ControlChange(
        val control: String,
        val from: String,
        val to: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        refreshAllStates()
        isInit = true
        Logger.d(name, "SmartControlCenter initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        refreshAllStates()
        Logger.d(name, "SmartControlCenter started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching { isRun = false }
    override suspend fun reset(): Result<Unit> = runCatching { _controlHistory.value = emptyList() }
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun refreshAllStates() {
        _controlsState.value = DeviceControlsState(
            wifi = getWifiState(),
            bluetooth = getBluetoothState(),
            location = getLocationState(),
            nfc = getNfcState(),
            mobileData = getMobileDataState(),
            flashlight = getFlashlightState(),
            hotspot = getHotspotState(),
            airplaneMode = getAirplaneModeState(),
            doNotDisturb = getDndState(),
            powerSave = getPowerSaveState(),
            screenRotation = getRotationState(),
            volumeLevel = getVolumeLevel(),
            brightnessLevel = getBrightnessLevel()
        )
    }
    
    private fun logChange(control: String, from: String, to: String) {
        _controlHistory.value = (_controlHistory.value + ControlChange(control, from, to)).takeLast(50)
    }
    
    // Wi-Fi
    fun toggleWifi(): Boolean {
        val current = getWifiState()
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
            logChange("Wi-Fi", current.name, if (wifiManager.isWifiEnabled) "ON" else "OFF")
            refreshAllStates()
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Logger.e(name, "Error toggling Wi-Fi", e)
            false
        }
    }
    
    fun setWifi(enabled: Boolean) {
        val current = getWifiState()
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = enabled
            logChange("Wi-Fi", current.name, if (enabled) "ON" else "OFF")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error setting Wi-Fi", e)
        }
    }
    
    private fun getWifiState(): ControlStatus {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiManager.isWifiEnabled) ControlStatus.ON else ControlStatus.OFF
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // Bluetooth
    fun toggleBluetooth(): Boolean {
        val current = getBluetoothState()
        return try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            logChange("Bluetooth", current.name, "ON")
            refreshAllStates()
            true
        } catch (e: Exception) {
            Logger.e(name, "Error toggling Bluetooth", e)
            false
        }
    }
    
    private fun getBluetoothState(): ControlStatus {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            when {
                adapter == null -> ControlStatus.UNKNOWN
                adapter.isEnabled -> ControlStatus.ON
                else -> ControlStatus.OFF
            }
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // Location
    fun toggleLocation(): Boolean {
        val current = getLocationState()
        return try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            logChange("Localização", current.name, if (current == ControlStatus.OFF) "ON" else "OFF")
            refreshAllStates()
            true
        } catch (e: Exception) {
            Logger.e(name, "Error toggling Location", e)
            false
        }
    }
    
    private fun getLocationState(): ControlStatus {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                ControlStatus.ON
            } else ControlStatus.OFF
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // NFC
    fun toggleNfc() {
        val current = getNfcState()
        try {
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            logChange("NFC", current.name, "SETTINGS")
        } catch (e: Exception) {
            Logger.e(name, "Error toggling NFC", e)
        }
    }
    
    private fun getNfcState(): ControlStatus {
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            when {
                adapter == null -> ControlStatus.UNKNOWN
                adapter.isEnabled -> ControlStatus.ON
                else -> ControlStatus.OFF
            }
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // Mobile Data
    private fun getMobileDataState(): ControlStatus {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                ControlStatus.ON
            } else ControlStatus.OFF
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // Flashlight
    fun toggleFlashlight(): Boolean {
        val current = getFlashlightState()
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                val newState = current != ControlStatus.ON
                cameraManager.setTorchMode(cameraId, newState)
                logChange("Lanterna", current.name, if (newState) "ON" else "OFF")
                refreshAllStates()
                newState
            } else false
        } catch (e: Exception) {
            Logger.e(name, "Error toggling flashlight", e)
            false
        }
    }
    
    private fun getFlashlightState(): ControlStatus {
        return ControlStatus.OFF // Would need listener for actual state
    }
    
    // Hotspot
    private fun getHotspotState(): ControlStatus = ControlStatus.UNKNOWN
    
    // Airplane Mode
    private fun getAirplaneModeState(): ControlStatus {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (Settings.System.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                    ControlStatus.ON
                } else ControlStatus.OFF
            } else {
                @Suppress("DEPRECATION")
                if (Settings.System.getInt(context.contentResolver, Settings.System.AIRPLANE_MODE_ON, 0) == 1) {
                    ControlStatus.ON
                } else ControlStatus.OFF
            }
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    // Do Not Disturb
    private fun getDndState(): ControlStatus {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            when (notificationManager.currentInterruptionFilter) {
                android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> ControlStatus.ON
                else -> ControlStatus.OFF
            }
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    fun toggleDnd() {
        try {
            val current = getDndState()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (current == ControlStatus.ON) {
                    notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                } else {
                    notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                }
            }
            logChange("Não Perturbe", current.name, if (current == ControlStatus.ON) "OFF" else "ON")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error toggling DND", e)
        }
    }
    
    // Power Save
    private fun getPowerSaveState(): ControlStatus {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isPowerSaveMode) ControlStatus.ON else ControlStatus.OFF
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    fun togglePowerSave() {
        try {
            val current = getPowerSaveState()
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            logChange("Economia Bateria", current.name, if (current == ControlStatus.ON) "OFF" else "ON")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error toggling power save", e)
        }
    }
    
    // Screen Rotation
    private fun getRotationState(): ControlStatus {
        return try {
            val rotation = android.provider.Settings.System.getInt(context.contentResolver, "rotation", 0)
            if (rotation == 1) ControlStatus.ON else ControlStatus.OFF
        } catch (e: Exception) { ControlStatus.UNKNOWN }
    }
    
    fun toggleRotation() {
        try {
            val current = getRotationState()
            val newState = current != ControlStatus.ON
            android.provider.Settings.System.putInt(context.contentResolver, "rotation", if (newState) 1 else 0)
            logChange("Rotação Tela", current.name, if (newState) "ON" else "OFF")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error toggling rotation", e)
        }
    }
    
    // Volume
    private fun getVolumeLevel(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        } catch (e: Exception) { -1 }
    }
    
    fun setVolume(level: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            val newLevel = level.coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, newLevel, 0)
            logChange("Volume", "${_controlsState.value.volumeLevel}", "$newLevel")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error setting volume", e)
        }
    }
    
    // Brightness
    private fun getBrightnessLevel(): Int {
        return try {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) { -1 }
    }
    
    fun setBrightness(level: Int) {
        try {
            val newLevel = level.coerceIn(0, 255)
            android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, newLevel)
            logChange("Brilho", "${_controlsState.value.brightnessLevel}", "$newLevel")
            refreshAllStates()
        } catch (e: Exception) {
            Logger.e(name, "Error setting brightness", e)
        }
    }
    
    // Quick actions
    fun quietMode() {
        toggleDnd()
        setVolume(0)
    }
    
    fun presentationMode() {
        toggleRotation()
        setBrightness(200)
    }
    
    fun sleepMode() {
        quietMode()
        if (getFlashlightState() == ControlStatus.ON) toggleFlashlight()
    }
}
