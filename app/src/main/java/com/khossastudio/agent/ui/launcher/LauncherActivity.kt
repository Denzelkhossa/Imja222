package com.khossastudio.agent.ui.launcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.khossastudio.agent.*
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.skills.SkillManager
import com.khossastudio.agent.smart.alarms.IntelligentAlarmSystem
import com.khossastudio.agent.smart.controls.SmartControlCenter
import com.khossastudio.agent.smart.personality.PersonalityEngine
import com.khossastudio.agent.ui.compose.KhossaTheme
import com.khossastudio.agent.voice.KhossaVoiceService
import com.khossastudio.agent.voice.VoiceInteractionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LauncherActivity - Nova tela principal do KhossaAgent
 * Interface estilo "Manos IA" / Launcher inteligente
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var skillManager: SkillManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var voiceManager: VoiceInteractionManager
    private lateinit var controlCenter: SmartControlCenter
    private lateinit var alarmSystem: IntelligentAlarmSystem
    private lateinit var personalityEngine: PersonalityEngine
    private lateinit var localProcessor: LocalCommandProcessor

    private var uiState by mutableStateOf(LauncherUiState())
    private val coroutineScope = lifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("khossa_agent_prefs", MODE_PRIVATE)
        skillManager = SkillManager(this)
        memoryManager = MemoryManager()
        voiceManager = VoiceInteractionManager(this)
        controlCenter = SmartControlCenter()
        alarmSystem = IntelligentAlarmSystem()
        personalityEngine = PersonalityEngine()
        localProcessor = LocalCommandProcessor(this)

        // Inicializar módulos
        coroutineScope.launch(Dispatchers.IO) {
            try {
                memoryManager.initialize(this@LauncherActivity)
            } catch (e: Exception) { }
            try {
                controlCenter.initialize(this@LauncherActivity)
            } catch (e: Exception) { }
            try {
                alarmSystem.initialize(this@LauncherActivity)
            } catch (e: Exception) { }
        }

        // Inicializar voz
        try { voiceManager.init() } catch (e: Exception) { }
        voiceManager.onLevel = { level -> uiState = uiState.copy(voiceLevel = level) }

        // Carregar estado inicial
        loadInitialState()

        setContent {
            KhossaTheme {
                KhossaLauncherScreen(
                    state = uiState,
                    prefs = prefs,
                    skillManager = skillManager,
                    memoryManager = memoryManager,
                    voiceManager = voiceManager,
                    controlCenter = controlCenter,
                    alarmSystem = alarmSystem,
                    personalityEngine = personalityEngine,
                    onToggleKhossa = { toggleKhossa() },
                    onToggleVoice = { toggleVoice() },
                    onToggleWakeWord = { toggleWakeWord() },
                    onToggleAutomation = { toggleAutomation() },
                    onToggleSafeMode = { toggleSafeMode() },
                    onMicClick = { onMicClick() },
                    onQuickAction = { action -> handleQuickAction(action) },
                    onOpenApp = { packageName -> openApp(packageName) },
                    onNavigateToSettings = { navigateToSettings() },
                    onNavigateToSkills = { navigateToSkills() },
                    onNavigateToMemory = { navigateToMemory() },
                    onNavigateToAutomation = { navigateToAutomation() },
                    onNavigateToApps = { navigateToApps() },
                    onNavigateToVoice = { navigateToVoice() },
                    onNavigateToAlarms = { navigateToAlarms() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadRecentApps()
        loadSkills()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }

    private fun loadInitialState() {
        uiState = uiState.copy(
            khossaEnabled = prefs.getBoolean("khossa_enabled", true),
            voiceEnabled = KhossaVoiceService.instanceRunning,
            wakeWordEnabled = prefs.getBoolean("wake_word_enabled", false),
            automationEnabled = prefs.getBoolean("automation_enabled", false),
            safeModeEnabled = prefs.getBoolean("safe_mode", true),
            statusText = getGreeting()
        )
    }

    private fun updateStatus() {
        uiState = uiState.copy(
            accessibilityActive = AgentAccessibilityService.instance != null,
            backgroundServiceActive = KhossaVoiceService.instanceRunning,
            mood = personalityEngine.mood.value.name
        )

        // Atualizar controles do dispositivo
        coroutineScope.launch {
            try {
                val state = controlCenter.controlsState.value
                uiState = uiState.copy(
                    controls = DeviceControlState(
                        wifi = state.wifi == SmartControlCenter.ControlStatus.ON,
                        bluetooth = state.bluetooth == SmartControlCenter.ControlStatus.ON,
                        flashlight = state.flashlight == SmartControlCenter.ControlStatus.ON,
                        location = state.location == SmartControlCenter.ControlStatus.ON,
                        dnd = state.doNotDisturb == SmartControlCenter.ControlStatus.ON,
                        powerSave = state.powerSave == SmartControlCenter.ControlStatus.ON
                    )
                )
            } catch (e: Exception) { }
        }
    }

    private fun getGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Bom dia!"
            hour < 18 -> "Boa tarde!"
            else -> "Boa noite!"
        }
    }

    private fun loadRecentApps() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val recentApps = memoryManager.mostUsedApp()
                val apps = mutableListOf<AppInfo>()
                
                recentApps?.let {
                    try {
                        val pm = packageManager
                        val appInfo = pm.getApplicationInfo(it.packageName, 0)
                        apps.add(AppInfo(
                            name = pm.getApplicationLabel(appInfo).toString(),
                            packageName = it.packageName,
                            icon = pm.getApplicationIcon(appInfo)
                        ))
                    } catch (e: Exception) { }
                }

                // Também adicionar apps do packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val installedApps = packageManager.queryIntentActivities(intent, 0)
                    .mapNotNull { resolveInfo ->
                        try {
                            val appInfo = resolveInfo.activityInfo.applicationInfo
                            AppInfo(
                                name = packageManager.getApplicationLabel(appInfo).toString(),
                                packageName = appInfo.packageName,
                                icon = packageManager.getApplicationIcon(appInfo)
                            )
                        } catch (e: Exception) { null }
                    }
                    .filter { it.packageName != packageName }
                    .distinctBy { it.packageName }
                    .take(8)

                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(recentApps = (apps + installedApps).take(8))
                }
            } catch (e: Exception) { }
        }
    }

    private fun loadSkills() {
        coroutineScope.launch {
            try {
                val skills = skillManager.listSkills()
                uiState = uiState.copy(skills = skills)
            } catch (e: Exception) { }
        }
    }

    // ==================== HANDLERS ====================

    private fun toggleKhossa() {
        val newState = !uiState.khossaEnabled
        prefs.edit().putBoolean("khossa_enabled", newState).apply()
        uiState = uiState.copy(khossaEnabled = newState)
        
        if (newState) {
            voiceManager.speak("Khossa ativado. Como posso ajudar?")
        } else {
            voiceManager.speak("Khossa desativado.")
        }
    }

    private fun toggleVoice() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
            return
        }

        val newState = !uiState.voiceEnabled
        uiState = uiState.copy(voiceEnabled = newState)
        prefs.edit().putBoolean("voice_enabled", newState).apply()
        
        if (newState) {
            voiceManager.speak("Assistente de voz ativado.")
        } else {
            voiceManager.speak("Assistente de voz desativado.")
        }
    }

    private fun toggleWakeWord() {
        val newState = !uiState.wakeWordEnabled
        prefs.edit().putBoolean("wake_word_enabled", newState).apply()
        uiState = uiState.copy(wakeWordEnabled = newState)

        if (newState) {
            if (AgentAccessibilityService.instance != null) {
                KhossaVoiceService.start(this)
                voiceManager.speak("Wake word ativado. Diga 'Olá, Khossa' para ativar.")
            } else {
                uiState = uiState.copy(wakeWordEnabled = false)
                voiceManager.speak("Ative o serviço de acessibilidade primeiro.")
            }
        } else {
            KhossaVoiceService.stop(this)
            voiceManager.speak("Wake word desativado.")
        }
    }

    private fun toggleAutomation() {
        val newState = !uiState.automationEnabled
        prefs.edit().putBoolean("automation_enabled", newState).apply()
        uiState = uiState.copy(automationEnabled = newState)
        
        if (newState) {
            voiceManager.speak("Automação ativada.")
        } else {
            voiceManager.speak("Automação desativada.")
        }
    }

    private fun toggleSafeMode() {
        val newState = !uiState.safeModeEnabled
        prefs.edit().putBoolean("safe_mode", newState).apply()
        uiState = uiState.copy(safeModeEnabled = newState)
        
        voiceManager.speak(if (newState) "Modo seguro ativado." else "Modo seguro desativado.")
    }

    private fun onMicClick() {
        if (uiState.listening) {
            voiceManager.stopListening()
            uiState = uiState.copy(listening = false, statusText = "Toque para falar")
            return
        }

        if (AgentAccessibilityService.instance == null) {
            uiState = uiState.copy(statusText = "Ative o serviço de acessibilidade primeiro")
            return
        }

        uiState = uiState.copy(listening = true, statusText = "A ouvir…")
        voiceManager.startContinuousListening { heard ->
            voiceManager.stopListening()
            uiState = uiState.copy(
                listening = false,
                thinking = true,
                statusText = "A pensar…"
            )

            // Processar comando local
            val localAction = localProcessor.process(heard)
            if (localAction != null) {
                coroutineScope.launch {
                    try {
                        val result = ActionExecutor.execute(AgentAccessibilityService.instance!!, localAction, memoryManager)
                        voiceManager.speak("Comando executado.")
                        uiState = uiState.copy(thinking = false, statusText = "Pronto")
                    } catch (e: Exception) {
                        uiState = uiState.copy(thinking = false, statusText = "Erro: ${e.message}")
                    }
                }
            } else {
                // Ir para AssistantActivity para comandos mais complexos
                uiState = uiState.copy(thinking = false, statusText = "Abrindo assistente…")
                startActivity(Intent(this, AssistantActivity::class.java))
            }
        }
    }

    private fun handleQuickAction(action: QuickAction) {
        when (action.action) {
            QuickActionType.OPEN_APPS -> navigateToApps()
            QuickActionType.AUTOMATION -> navigateToAutomation()
            QuickActionType.MEMORY -> navigateToMemory()
            QuickActionType.SKILLS -> navigateToSkills()
            QuickActionType.SETTINGS -> navigateToSettings()
            QuickActionType.VOICE -> navigateToVoice()
            QuickActionType.RUN_COMMAND -> onMicClick()
            QuickActionType.CLEAR_MEMORY -> clearMemory()
        }
    }

    private fun clearMemory() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                memoryManager.reset()
                withContext(Dispatchers.Main) {
                    voiceManager.speak("Memória limpa com sucesso.")
                    uiState = uiState.copy(memories = emptyList())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    voiceManager.speak("Erro ao limpar memória.")
                }
            }
        }
    }

    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                // Registrar uso do app
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val appName = packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                        memoryManager.recordApp(packageName, appName)
                    } catch (e: Exception) { }
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            uiState = uiState.copy(statusText = "Não foi possível abrir o app")
        }
    }

    // ==================== NAVEGAÇÃO ====================

    private fun navigateToSettings() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun navigateToSkills() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("tab", "skills")
        })
    }

    private fun navigateToMemory() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("tab", "memory")
        })
    }

    private fun navigateToAutomation() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("tab", "automation")
        })
    }

    private fun navigateToApps() {
        // Abrir seletor de apps
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        startActivity(Intent.createChooser(intent, "Abrir app"))
    }

    private fun navigateToVoice() {
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    private fun navigateToAlarms() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("tab", "alarms")
        })
    }
}
