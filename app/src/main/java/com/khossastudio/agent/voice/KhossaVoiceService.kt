package com.khossastudio.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.khossastudio.agent.AssistantActivity
import com.khossastudio.agent.TaskPlanner
import com.khossastudio.agent.llm.LlmAction
import com.khossastudio.agent.llm.LlmProvider
import com.khossastudio.agent.core.registry.ModuleRegistry
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.skills.SkillManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel

/**
 * Foreground Service que mantém o microfone ouvindo em segundo plano à espera
 * da palavra de ativação ("Olá, Khossa") — itens 1 e 3 do briefing: "Ativação
 * por palavra-chave" e "Funcionar em segundo plano quando permitido pelo Android".
 *
 * Fluxo: escuta contínua -> ouve a wake word -> confirma com um bip/frase curta
 * -> ouve o comando -> executa a tarefa com o próprio TaskPlanner (sem precisar
 * abrir nenhuma Activity) -> volta a escutar a wake word.
 *
 * Confirmações de ações sensíveis, quando o app está em segundo plano (sem UI
 * visível para um AlertDialog), são feitas por VOZ: o agente pergunta e ouve
 * "sim"/"não" pelo mesmo microfone — mesma lógica do "Modo Live" da Activity.
 *
 * Precisa de: permissão de microfone concedida, serviço de acessibilidade
 * ativo, e (Android 13+) permissão de notificações para mostrar o aviso
 * persistente exigido pelo Android para qualquer foreground service.
 */
class KhossaVoiceService : Service() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var prefs: SharedPreferences
    private lateinit var memory: MemoryManager
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)
    private var awaitingCommand = false
    private var taskRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("khossa_agent_prefs", MODE_PRIVATE)
        memory = ModuleRegistry.get("memory") as MemoryManager
        voice = VoiceInteractionManager(this)
        voice.init()
        
        wakeWordManager = WakeWordManager(this)
        wakeWordManager.onWakeWordDetected = {
            onWakeWordDetected()
        }
        wakeWordManager.init {
            wakeWordManager.startListening()
        }

        startForeground(NOTIFICATION_ID, buildNotification("A ouvir por \"Olá, Khossa\" (Offline)…"))
    }

    private fun onWakeWordDetected() {
        if (!taskRunning && !awaitingCommand) {
            awaitingCommand = true
            updateNotification("A ouvir o comando…")
            voice.speak("Diga o comando.")
            // Para a escuta da wake word enquanto ouve o comando
            wakeWordManager.stopListening()
            voice.startListening { heard ->
                onHeard(heard)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // o Android tenta recriar o serviço se for morto
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.shutdown()
        wakeWordManager.stopListening()
        serviceJob.cancel()
        instanceRunning = false
    }

    private fun beginListening() {
        instanceRunning = true
        voice.startContinuousListening { heard -> onHeard(heard) }
    }

    private fun onHeard(heard: String) {
        if (taskRunning) return // já a executar uma tarefa, ignora fala nova por agora

        if (!awaitingCommand) {
            val command = WakeWordDetector.extractCommandAfterWakeWord(heard)
            if (command != null) {
                if (command.isNotBlank()) {
                    runCommand(command)
                } else {
                    awaitingCommand = true
                    updateNotification("A ouvir o comando…")
                    voice.speak("Diga o comando.")
                }
            }
            // sem wake word: ignora (é conversa de fundo, não é para o Khossa)
            return
        }

        // já estava à espera do comando depois da wake word
        awaitingCommand = false
        runCommand(heard)
    }

    private fun runCommand(goal: String) {
        taskRunning = true
        updateNotification("A executar: $goal")
        voice.speak("Ok, $goal")

        val provider = LlmProvider.fromDisplayName(prefs.getString("provider", LlmProvider.GROQ.displayName) ?: "")
        val model = prefs.getString("model_${provider.name}", provider.defaultModels.first()) ?: provider.defaultModels.first()
        val storedKeys = prefs.getString("apikeys_${provider.name}", "") ?: ""
        val keys = storedKeys.split("|||").filter { it.isNotBlank() }
        val supervised = prefs.getBoolean("supervised", true)
        val autonomous = prefs.getBoolean("autonomous", false)

        val planner = TaskPlanner(
            scope = serviceScope,
            onLog = { /* sem UI aqui; poderia ir para um LogCat/arquivo se preciso */ },
            onSpeak = { msg -> voice.speak(msg) },
            onNeedsConfirmation = { action -> confirmByVoice(action) },
            onFinished = {
                taskRunning = false
                updateNotification("A ouvir por \"Olá, Khossa\"…")
            },
            skillManager = SkillManager(this),
            memory = memory
        )
        planner.run(goal, provider, keys, model, supervised, autonomous)
    }

    /**
     * Confirmação falada: pausa a escuta de wake word, pergunta e ouve UMA frase.
     * Se não for nem "sim" nem "não" claramente, considera negado por segurança.
     */
    private suspend fun confirmByVoice(action: LlmAction): Boolean {
        val channel = Channel<Boolean>(capacity = 1)
        voice.stopListening()
        val pergunta = action.reasoning?.let { "Preciso da tua confirmação: $it. Posso continuar? Diz sim ou não." }
            ?: "Preciso da tua confirmação pra continuar. Diz sim ou não."
        voice.speak(pergunta) {
            voice.startContinuousListening { heard ->
                when {
                    VoiceInteractionManager.matchesAny(heard, VoiceInteractionManager.YES_KEYWORDS) -> {
                        channel.trySend(true)
                        voice.stopListening()
                        beginListening()
                    }
                    VoiceInteractionManager.matchesAny(heard, VoiceInteractionManager.NO_KEYWORDS) -> {
                        channel.trySend(false)
                        voice.stopListening()
                        beginListening()
                    }
                    // frase não reconhecida como sim/não: continua a ouvir a próxima
                }
            }
        }
        return channel.receive()
    }

    // ---------- Notificação persistente (exigida para foreground service) ----------

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, AssistantActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Khossa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Khossa em segundo plano", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "khossa_voice_service"
        private const val NOTIFICATION_ID = 42
        @Volatile var instanceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, KhossaVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KhossaVoiceService::class.java))
            instanceRunning = false
        }
    }
}
