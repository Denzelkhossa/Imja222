package com.khossastudio.agent.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Intelligent Permission Manager for KhossaAgent.
 * Detects, explains, requests, and monitors permissions.
 */
class PermissionManager : KhossaModule {
    override val name = "permissions"
    override val version = "1.0.0"
    override val dependencies = listOf("core")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    
    private val _permissions = MutableStateFlow<Map<String, PermissionState>>(emptyMap())
    val permissions: StateFlow<Map<String, PermissionState>> = _permissions.asStateFlow()
    
    data class PermissionState(
        val name: String,
        val granted: Boolean,
        val requested: Boolean,
        val rationale: String?,
        val features: List<String>
    )
    
    // Permission definitions with rationale and affected features
    private val permissionDefs = mapOf(
        Manifest.permission.RECORD_AUDIO to PermissionDef(
            "Microfone",
            "Para ouvir comandos de voz e palavra de ativação",
            listOf("voice_input", "wake_word", "voice_commands")
        ),
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE to PermissionDef(
            "Acessibilidade",
            "Para ler e controlar a interface do Android",
            listOf("ui_navigation", "action_execution", "screen_analysis")
        ),
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE to PermissionDef(
            "Notificações",
            "Para ler notificações e controlar música",
            listOf("notification_reading", "media_control")
        ),
        Manifest.permission.CAMERA to PermissionDef(
            "Câmara",
            "Para análise visual e OCR",
            listOf("vision", "ocr", "screen_description")
        ),
        Manifest.permission.ACCESS_FINE_LOCATION to PermissionDef(
            "Localização",
            "Para automações baseadas em local",
            listOf("location_automation", "context_awareness")
        ),
        Manifest.permission.ACCESS_COARSE_LOCATION to PermissionDef(
            "Localização aproximada",
            "Para contexto de local",
            listOf("context_awareness")
        ),
        Manifest.permission.BLUETOOTH to PermissionDef(
            "Bluetooth",
            "Para conectar a dispositivos Bluetooth",
            listOf("bluetooth_control", "device_automation")
        ),
        Manifest.permission.BLUETOOTH_CONNECT to PermissionDef(
            "Bluetooth Connect",
            "Para controlar dispositivos Bluetooth conectados",
            listOf("bluetooth_control", "device_automation")
        ),
        Manifest.permission.POST_NOTIFICATIONS to PermissionDef(
            "Notificações do app",
            "Para informar sobre ações e lembretes",
            listOf("notifications", "reminders", "alerts")
        ),
        Manifest.permission.SCHEDULE_EXACT_ALARM to PermissionDef(
            "Alarmes exatos",
            "Para lembretes no horário exacto",
            listOf("reminders", "alarms")
        ),
        Manifest.permission.VIBRATE to PermissionDef(
            "Vibração",
            "Para feedback tátil",
            listOf("haptic_feedback")
        )
    )
    
    data class PermissionDef(
        val displayName: String,
        val rationale: String,
        val features: List<String>
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        refreshAllPermissions()
        isInit = true
        Logger.d(name, "PermissionManager initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "PermissionManager started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        Logger.d(name, "PermissionManager stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        refreshAllPermissions()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    /**
     * Refresh permission state for all known permissions.
     */
    fun refreshAllPermissions() {
        val states = mutableMapOf<String, PermissionState>()
        for ((perm, def) in permissionDefs) {
            val granted = isGranted(perm)
            states[perm] = PermissionState(
                name = def.displayName,
                granted = granted,
                requested = wasRequested(perm),
                rationale = def.rationale,
                features = def.features
            )
        }
        _permissions.value = states
    }
    
    /**
     * Check if a permission is granted.
     */
    fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if a permission was previously requested.
     */
    private fun wasRequested(permission: String): Boolean {
        val prefs = context.getSharedPreferences("khossa_permissions", Context.MODE_PRIVATE)
        return prefs.getBoolean("requested_$permission", false)
    }
    
    /**
     * Mark a permission as requested.
     */
    fun markRequested(permission: String) {
        context.getSharedPreferences("khossa_permissions", Context.MODE_PRIVATE)
            .edit().putBoolean("requested_$permission", true).apply()
    }
    
    /**
     * Get features affected by missing permissions.
     */
    fun getAffectedFeatures(): List<String> {
        return _permissions.value.filter { !it.value.granted }
            .flatMap { it.value.features }
            .distinct()
    }
    
    /**
     * Get unavailable features due to permissions.
     */
    fun getUnavailableFeatures(): List<String> {
        val allFeatures = permissionDefs.values.flatMap { it.features }.distinct()
        val grantedFeatures = _permissions.value.filter { it.value.granted }
            .flatMap { it.value.features }.toSet()
        return allFeatures.filter { it !in grantedFeatures }
    }
    
    /**
     * Check if a specific feature is available.
     */
    fun isFeatureAvailable(feature: String): Boolean {
        return _permissions.value.none { !it.value.granted && it.value.features.contains(feature) }
    }
    
    /**
     * Get rationale for a permission.
     */
    fun getRationale(permission: String): String? {
        return permissionDefs[permission]?.rationale
    }
    
    /**
     * Get display name for a permission.
     */
    fun getDisplayName(permission: String): String {
        return permissionDefs[permission]?.displayName ?: permission.substringAfterLast(".")
    }
    
    /**
     * Notify permission change (call after runtime permission result).
     */
    fun onPermissionResult(permission: String, granted: Boolean) {
        markRequested(permission)
        refreshAllPermissions()
        EventBus.publish(EventBus.PermissionChangedEvent(permission, granted))
    }
    
    /**
     * Get all required permissions for a feature.
     */
    fun getRequiredPermissions(feature: String): List<String> {
        return permissionDefs.filter { it.value.features.contains(feature) }
            .map { it.key }
    }
    
    /**
     * Get missing permissions for a feature.
     */
    fun getMissingPermissions(feature: String): List<String> {
        return getRequiredPermissions(feature).filter { !isGranted(it) }
    }
}
