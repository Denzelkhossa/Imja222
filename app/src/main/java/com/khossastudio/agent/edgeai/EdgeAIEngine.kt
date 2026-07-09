package com.khossastudio.agent.edgeai

import android.content.Context
import com.khossastudio.agent.core.config.AppConfig
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edge AI Engine - Fast local command processing.
 * Processes commands locally in <100ms when possible.
 */
class EdgeAIEngine : KhossaModule {
    override val name = "edgeai"
    override val version = "1.0.0"
    override val dependencies = listOf("core", "permissions", "capabilities")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    
    private val _processingTime = MutableStateFlow<Long>(0)
    val processingTime: StateFlow<Long> = _processingTime.asStateFlow()
    
    private val _intentResult = MutableStateFlow<IntentResult?>(null)
    val intentResult: StateFlow<IntentResult?> = _intentResult.asStateFlow()
    
    private val commandCache = mutableMapOf<String, CachedIntent>()
    
    data class IntentResult(
        val intent: Intent,
        val confidence: Float,
        val params: Map<String, String>,
        val isOffline: Boolean,
        val processingTimeMs: Long
    )
    
    data class CachedIntent(
        val command: String,
        val intent: Intent,
        val timestamp: Long
    )
    
    enum class Intent {
        NAVIGATION_BACK,
        NAVIGATION_HOME,
        VOLUME_UP,
        VOLUME_DOWN,
        VOLUME_MUTE,
        BRIGHTNESS_UP,
        BRIGHTNESS_DOWN,
        WIFI_ON,
        WIFI_OFF,
        BLUETOOTH_ON,
        BLUETOOTH_OFF,
        FLASHLIGHT_ON,
        FLASHLIGHT_OFF,
        BATTERY_INFO,
        TIME_INFO,
        OPEN_APP,
        CREATE_ALARM,
        SEND_MESSAGE,
        MAKE_CALL,
        CONTROL_MEDIA,
        VISION_ANALYZE,
        OCR,
        UNKNOWN
    }
    
    // Intent patterns with keywords
    private val intentPatterns = mapOf(
        Intent.NAVIGATION_BACK to listOf("voltar", "back", "retroceder", "anterior"),
        Intent.NAVIGATION_HOME to listOf("home", "início", "tela inicial", "menu principal"),
        Intent.VOLUME_UP to listOf("aumentar volume", "mais alto", "volume +", "sobe o som"),
        Intent.VOLUME_DOWN to listOf("diminuir volume", "mais baixo", "volume -", "baixa o som"),
        Intent.VOLUME_MUTE to listOf("silencioso", "mudo", "mutar", "sem som"),
        Intent.BRIGHTNESS_UP to listOf("aumentar brilho", "mais brilho", "brilho +"),
        Intent.BRIGHTNESS_DOWN to listOf("diminuir brilho", "menos brilho", "brilho -"),
        Intent.WIFI_ON to listOf("ligar wifi", "ativar wifi", "wifi on"),
        Intent.WIFI_OFF to listOf("desligar wifi", "desativar wifi", "wifi off"),
        Intent.BLUETOOTH_ON to listOf("ligar bluetooth", "ativar bluetooth", "bluetooth on"),
        Intent.BLUETOOTH_OFF to listOf("desligar bluetooth", "desativar bluetooth", "bluetooth off"),
        Intent.FLASHLIGHT_ON to listOf("ligar lanterna", "ativar lanterna", "lanterna on"),
        Intent.FLASHLIGHT_OFF to listOf("desligar lanterna", "apagar lanterna", "lanterna off"),
        Intent.BATTERY_INFO to listOf("bateria", "carga", "energia", "quanto tenho"),
        Intent.TIME_INFO to listOf("que horas", "qual a data", "que dia", "horas"),
        Intent.CONTROL_MEDIA to listOf("pausar", "tocar", "play", "próxima", "anterior", "parar música")
    )
    
    // App opening patterns
    private val appPatterns = mapOf(
        "whatsapp" to listOf("whatsapp", "zap"),
        "chrome" to listOf("chrome", "navegador", "browser"),
        "câmera" to listOf("câmera", "camera", "fotos"),
        "mensagens" to listOf("mensagens", "sms"),
        "galeria" to listOf("galeria", "fotos", "imagens"),
        "telefone" to listOf("telefone", "chamadas", "discador")
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        _enabled.value = AppConfig.getBoolean(AppConfig.Keys.EDGE_AI_ENABLED)
        isInit = true
        Logger.d(name, "EdgeAIEngine initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "EdgeAIEngine started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        Logger.d(name, "EdgeAIEngine stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        commandCache.clear()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    /**
     * Process a voice/text command locally.
     * @param command The input command
     * @return IntentResult if recognized, null if should use cloud
     */
    suspend fun processCommand(command: String): IntentResult? = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        val normalized = command.lowercase().trim()
        
        // Check cache first
        commandCache[normalized]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < 300_000) { // 5 min cache
                val time = System.currentTimeMillis() - startTime
                return@withContext IntentResult(
                    intent = cached.intent,
                    confidence = 0.95f,
                    params = emptyMap(),
                    isOffline = true,
                    processingTimeMs = time
                )
            }
        }
        
        // Try to classify intent
        val result = classifyIntent(normalized)
        
        val processingTime = System.currentTimeMillis() - startTime
        _processingTime.value = processingTime
        
        // Cache the result
        if (result != null) {
            commandCache[normalized] = CachedIntent(normalized, result.intent, System.currentTimeMillis())
        }
        
        EventBus.publish(EventBus.CommandReceivedEvent(command, result != null))
        
        if (result != null && processingTime <= 100) {
            Logger.d(name, "Fast local response: ${result.intent} in ${processingTime}ms")
        }
        
        result?.copy(processingTimeMs = processingTime)
    }
    
    /**
     * Classify the intent from the command.
     */
    private fun classifyIntent(command: String): IntentResult? {
        // Check navigation intents
        for ((intent, patterns) in intentPatterns) {
            if (patterns.any { command.contains(it) }) {
                val params = extractParams(command, intent)
                return IntentResult(
                    intent = intent,
                    confidence = 0.9f,
                    params = params,
                    isOffline = true,
                    processingTimeMs = 0
                )
            }
        }
        
        // Check app opening
        val app = detectApp(command)
        if (app != null) {
            return IntentResult(
                intent = Intent.OPEN_APP,
                confidence = 0.85f,
                params = mapOf("app" to app),
                isOffline = true,
                processingTimeMs = 0
            )
        }
        
        // Check alarm creation
        if (command.contains("alarme") || command.contains("lembrete")) {
            val params = extractTimeParams(command)
            return IntentResult(
                intent = Intent.CREATE_ALARM,
                confidence = 0.8f,
                params = params,
                isOffline = true,
                processingTimeMs = 0
            )
        }
        
        // Vision commands
        if (command.contains("ocr") || command.contains("lê") || command.contains("ler")) {
            return IntentResult(
                intent = Intent.OCR,
                confidence = 0.75f,
                params = mapOf("mode" to "text"),
                isOffline = false, // Requires cloud for actual OCR
                processingTimeMs = 0
            )
        }
        
        if (command.contains("descreve") || command.contains("o que é") || command.contains("deteta")) {
            return IntentResult(
                intent = Intent.VISION_ANALYZE,
                confidence = 0.75f,
                params = mapOf("mode" to "objects"),
                isOffline = false,
                processingTimeMs = 0
            )
        }
        
        return null
    }
    
    /**
     * Extract parameters from command based on intent.
     */
    private fun extractParams(command: String, intent: Intent): Map<String, String> {
        return when (intent) {
            Intent.VOLUME_UP -> mapOf("direction" to "up")
            Intent.VOLUME_DOWN -> mapOf("direction" to "down")
            Intent.VOLUME_MUTE -> mapOf("direction" to "mute")
            Intent.BRIGHTNESS_UP -> mapOf("direction" to "up")
            Intent.BRIGHTNESS_DOWN -> mapOf("direction" to "down")
            Intent.WIFI_ON, Intent.BLUETOOTH_ON, Intent.FLASHLIGHT_ON -> mapOf("on" to "true")
            Intent.WIFI_OFF, Intent.BLUETOOTH_OFF, Intent.FLASHLIGHT_OFF -> mapOf("on" to "false")
            Intent.CONTROL_MEDIA -> {
                val op = when {
                    command.contains("pausar") || command.contains("parar") -> "pause"
                    command.contains("tocar") || command.contains("play") -> "play"
                    command.contains("próxima") || command.contains("pular") -> "next"
                    command.contains("anterior") || command.contains("voltar") -> "previous"
                    else -> "pause"
                }
                mapOf("op" to op)
            }
            else -> emptyMap()
        }
    }
    
    /**
     * Extract time parameters for alarms/reminders.
     */
    private fun extractTimeParams(command: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        // Try to extract hour
        val hourMatch = Regex("(\\d{1,2})h").find(command)
        hourMatch?.let {
            params["hour"] = it.groupValues[1]
        }
        
        // Try to extract minute
        val minuteMatch = Regex("(\\d{1,2})min").find(command)
        minuteMatch?.let {
            params["minute"] = it.groupValues[1]
        }
        
        return params
    }
    
    /**
     * Detect app name from command.
     */
    private fun detectApp(command: String): String? {
        val prefixes = listOf("abrir ", "abre o ", "abre a ", "lançar ", "go to ", "iniciar ")
        for (prefix in prefixes) {
            if (command.startsWith(prefix)) {
                val appName = command.removePrefix(prefix).trim()
                // Try to match known apps
                for ((app, patterns) in appPatterns) {
                    if (patterns.any { appName.contains(it) }) {
                        return app
                    }
                }
                return appName
            }
        }
        return null
    }
    
    /**
     * Enable or disable Edge AI processing.
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        AppConfig.set(AppConfig.Keys.EDGE_AI_ENABLED, enabled)
    }
    
    /**
     * Get processing statistics.
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to _enabled.value,
            "avgProcessingTimeMs" to _processingTime.value,
            "cacheSize" to commandCache.size,
            "supportedIntents" to Intent.entries.size
        )
    }
}
