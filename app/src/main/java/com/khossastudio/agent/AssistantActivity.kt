package com.khossastudio.agent

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.khossastudio.agent.llm.LlmAction
import com.khossastudio.agent.llm.LlmProvider
import com.khossastudio.agent.core.registry.ModuleRegistry
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.skills.SkillManager
import com.khossastudio.agent.ui.compose.AssistantUiState
import com.khossastudio.agent.ui.compose.ConversationEntry
import com.khossastudio.agent.ui.compose.KhossaAssistantScreen
import com.khossastudio.agent.ui.compose.KhossaTheme
import com.khossastudio.agent.voice.KhossaVoiceService
import com.khossastudio.agent.voice.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Ecrã principal do app (item 7 do briefing: "interface moderna"), em Jetpack
 * Compose — botão de falar, animação de "a ouvir", histórico de conversas e
 * atalhos para configurações/permissões. As definições técnicas avançadas
 * (provedor de IA, chaves, skills, log bruto) continuam em [MainActivity].
 */
class AssistantActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var memory: MemoryManager
    private lateinit var voice: VoiceInteractionManager
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var planner: TaskPlanner? = null

    private var uiState by mutableStateOf(AssistantUiState())
    private var pendingConfirmation by mutableStateOf<LlmAction?>(null)
    private var confirmationChannel: Channel<Boolean>? = null
    private lateinit var localProcessor: LocalCommandProcessor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* resultado tratado via checkPermissions() no onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("khossa_agent_prefs", MODE_PRIVATE)
        
        // Safe initialization - create MemoryManager if not registered
        memory = (ModuleRegistry.get("memory") as? MemoryManager) ?: MemoryManager()
        
        voice = VoiceInteractionManager(this)
        try { voice.init() } catch (e: Exception) { /* TTS may fail on some devices */ }
        voice.onLevel = { level -> uiState = uiState.copy(voiceLevel = level) }
        localProcessor = LocalCommandProcessor(this)

        setContent {
            val state = uiState
            val pending = pendingConfirmation
            KhossaAssistantScreen(
                state = state,
                onMicClick = { onMicClick() },
                onOpenAdvancedSettings = { startActivity(Intent(this, MainActivity::class.java)) },
                onOpenPermissions = { openPermissionsFlow() },
                onToggleBackgroundService = { toggleBackgroundService() }
            )
            if (pending != null) {
                KhossaTheme {
                    AlertDialog(
                        onDismissRequest = { respondToConfirmation(false) },
                        title = { Text("Confirmar ação do Khossa") },
                        text = {
                            Column {
                                Text("Ação: ${pending.action}")
                                pending.target?.let { Text("Alvo: $it") }
                                pending.text?.let { Text("Texto: $it") }
                                pending.reasoning?.let { Text("Motivo: $it", modifier = androidx.compose.ui.Modifier.padding(top = 6.dp)) }
                            }
                        },
                        confirmButton = { TextButton(onClick = { respondToConfirmation(true) }) { Text("Permitir") } },
                        dismissButton = { TextButton(onClick = { respondToConfirmation(false) }) { Text("Bloquear") } }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiState = uiState.copy(
            accessibilityActive = AgentAccessibilityService.instance != null,
            backgroundServiceActive = KhossaVoiceService.instanceRunning
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.shutdown()
    }

    // ---------- Falar um comando único (toque no microfone) ----------

    private fun onMicClick() {
        if (uiState.listening) {
            voice.stopListening()
            uiState = uiState.copy(listening = false, statusText = "Toque para falar")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            return
        }
        if (AgentAccessibilityService.instance == null) {
            uiState = uiState.copy(statusText = "Ative o serviço de acessibilidade primeiro")
            return
        }

        uiState = uiState.copy(listening = true, statusText = "A ouvir…")
        voice.startContinuousListening { heard ->
            voice.stopListening()
            uiState = uiState.copy(
                listening = false,
                thinking = true,
                statusText = "A pensar…",
                history = uiState.history + ConversationEntry(fromUser = true, text = heard)
            )
            
            // Tenta processar localmente primeiro (Edge AI)
            val localAction = localProcessor.process(heard)
            if (localAction != null) {
                executeLocalAction(localAction)
            } else {
                runGoal(heard)
            }
        }
    }

    private fun executeLocalAction(action: LlmAction) {
        uiScope.launch {
            uiState = uiState.copy(thinking = true, statusText = "A executar localmente...")
            val result = ActionExecutor.execute(AgentAccessibilityService.instance!!, action, memory)
            voice.speak("Comando local executado: ${action.reasoning}")
            uiState = uiState.copy(
                thinking = false,
                statusText = "Toque para falar",
                history = uiState.history + ConversationEntry(false, "⚡ [Edge AI] $result")
            )
        }
    }

    private fun runGoal(goal: String) {
        val provider = LlmProvider.fromDisplayName(prefs.getString("provider", LlmProvider.GROQ.displayName) ?: "")
        val model = prefs.getString("model_${provider.name}", provider.defaultModels.first()) ?: provider.defaultModels.first()
        val keys = (prefs.getString("apikeys_${provider.name}", "") ?: "").split("|||").filter { it.isNotBlank() }
        val supervised = prefs.getBoolean("supervised", true)
        val autonomous = prefs.getBoolean("autonomous", false)

        if (keys.isEmpty()) {
            uiState = uiState.copy(
                thinking = false,
                statusText = "Toque para falar",
                history = uiState.history + ConversationEntry(false, "⚠️ Configure uma chave de API em Configurações avançadas primeiro.")
            )
            return
        }

        planner = TaskPlanner(
            scope = uiScope,
            onLog = { /* log técnico fica só no ecrã avançado; aqui só o essencial via onSpeak */ },
            onSpeak = { msg ->
                voice.speak(msg)
                uiState = uiState.copy(history = uiState.history + ConversationEntry(false, msg))
            },
            onNeedsConfirmation = { action -> awaitConfirmation(action) },
            onFinished = {
                uiState = uiState.copy(thinking = false, statusText = "Toque para falar")
            },
            skillManager = SkillManager(this),
            memory = memory
        )
        planner?.run(goal, provider, keys, model, supervised, autonomous)
    }

    private suspend fun awaitConfirmation(action: LlmAction): Boolean {
        val channel = Channel<Boolean>(capacity = 1)
        confirmationChannel = channel
        pendingConfirmation = action
        return channel.receive()
    }

    private fun respondToConfirmation(approved: Boolean) {
        confirmationChannel?.trySend(approved)
        confirmationChannel = null
        pendingConfirmation = null
    }

    // ---------- Permissões ----------

    private fun openPermissionsFlow() {
        val missing = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(android.Manifest.permission.RECORD_AUDIO)
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(android.Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(android.Manifest.permission.POST_NOTIFICATIONS)

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (AgentAccessibilityService.instance == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!KhossaNotificationListenerService.isEnabled(this)) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            return
        }
        // tudo concedido: mostra o estado como uma mensagem no histórico
        uiState = uiState.copy(
            history = uiState.history + ConversationEntry(
                false,
                "✅ Permissões principais concedidas (microfone, contactos, notificações, acessibilidade)."
            )
        )
    }

    // ---------- Escuta em segundo plano (wake word) ----------

    private fun toggleBackgroundService() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            return
        }
        if (AgentAccessibilityService.instance == null) {
            uiState = uiState.copy(statusText = "Ative o serviço de acessibilidade primeiro")
            return
        }
        if (KhossaVoiceService.instanceRunning) {
            KhossaVoiceService.stop(this)
        } else {
            KhossaVoiceService.start(this)
        }
        uiState = uiState.copy(backgroundServiceActive = !uiState.backgroundServiceActive)
    }
}
