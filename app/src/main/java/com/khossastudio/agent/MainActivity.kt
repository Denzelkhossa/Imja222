package com.khossastudio.agent

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.khossastudio.agent.config.SecretKeys
import com.khossastudio.agent.databinding.ActivityMainBinding
import com.khossastudio.agent.llm.LlmAction
import com.khossastudio.agent.llm.LlmProvider
import com.khossastudio.agent.core.registry.ModuleRegistry
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.skills.Skill
import com.khossastudio.agent.skills.SkillManager
import com.khossastudio.agent.voice.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var skillManager: SkillManager
    private lateinit var memory: MemoryManager
    private lateinit var voice: VoiceInteractionManager
    private var pendingVoiceConfirmation: ((String) -> Unit)? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var planner: TaskPlanner? = null

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = results?.firstOrNull()
        if (spoken != null) binding.etCommand.setText(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("khossa_agent_prefs", MODE_PRIVATE)
        skillManager = SkillManager(this)
        
        // Safe initialization - create MemoryManager if not registered
        memory = (ModuleRegistry.get("memory") as? MemoryManager) ?: MemoryManager()
        
        voice = VoiceInteractionManager(this)
        try { voice.init() } catch (e: Exception) { /* TTS may fail on some devices */ }

        setupProviderSpinner()
        restoreSavedValues()
        requestNativeToolPermissions()

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnMic.setOnClickListener { startVoiceInput() }
        binding.btnSkills.setOnClickListener { showSkillsDialog() }
        binding.btnOpenAssistant.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
        binding.btnNotificationAccess.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        binding.btnExactAlarm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
            } else {
                appendLog("ℹ️ Alarmes exatos não precisam de permissão especial nesta versão do Android.")
            }
        }

        binding.btnRun.setOnClickListener { onRunClicked() }
        binding.btnStop.setOnClickListener {
            planner?.stop()
            voice.stopListening()
            voice.stopSpeaking()
            appendLog("⏹️ Parado pelo usuário.")
        }

        binding.spinnerProvider.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                updateModelSuggestions()
                loadApiKeyForCurrentProvider()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        val active = AgentAccessibilityService.instance != null
        binding.tvServiceStatus.text = if (active) "Status: ✅ serviço ativo"
        else "Status: ❌ serviço inativo - toque no botão acima"
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.shutdown()
    }

    private fun setupProviderSpinner() {
        val names = LlmProvider.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerProvider.adapter = adapter
    }

    private fun updateModelSuggestions() {
        val provider = currentProvider()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, provider.defaultModels)
        binding.actModel.setAdapter(adapter)
        if (binding.actModel.text.isNullOrBlank()) {
            binding.actModel.setText(provider.defaultModels.first(), false)
        }
    }

    private fun currentProvider(): LlmProvider {
        val name = binding.spinnerProvider.selectedItem as? String ?: return LlmProvider.GROQ
        return LlmProvider.fromDisplayName(name)
    }

    private fun restoreSavedValues() {
        val savedProviderName = prefs.getString("provider", LlmProvider.GROQ.displayName)
        val index = LlmProvider.values().indexOfFirst { it.displayName == savedProviderName }
        if (index >= 0) binding.spinnerProvider.setSelection(index)
        updateModelSuggestions()
        prefs.getString("model_${currentProvider().name}", null)?.let { binding.actModel.setText(it) }
        loadApiKeyForCurrentProvider()
        binding.cbSupervised.isChecked = prefs.getBoolean("supervised", true)
        binding.cbAutonomous.isChecked = prefs.getBoolean("autonomous", false)
        binding.cbLiveMode.isChecked = prefs.getBoolean("live_mode", false)
    }

    private fun loadApiKeyForCurrentProvider() {
        // Chaves são salvas com "|||" como separador para suportar várias por provedor
        val stored = prefs.getString("apikeys_${currentProvider().name}", "") ?: ""
        var keys = stored.split("|||").filter { it.isNotBlank() }
        // Primeira vez com o provedor Gemini selecionado e sem chaves guardadas:
        // sugere as chaves de fábrica (secrets.properties) para rotação automática.
        if (keys.isEmpty()) {
            when (currentProvider()) {
                LlmProvider.GEMINI -> if (SecretKeys.defaultGeminiKeys.isNotEmpty()) keys = SecretKeys.defaultGeminiKeys
                LlmProvider.NVIDIA -> if (SecretKeys.defaultNvidiaKeys.isNotEmpty()) keys = SecretKeys.defaultNvidiaKeys
                else -> {}
            }
        }
        binding.etApiKey.setText(keys.joinToString("\n"))
    }

    /** Extrai a lista de chaves digitadas (uma por linha, ou separadas por vírgula). */
    private fun parseApiKeys(): List<String> {
        return binding.etApiKey.text.toString()
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun saveCurrentValues() {
        val provider = currentProvider()
        val keys = parseApiKeys()
        prefs.edit()
            .putString("provider", provider.displayName)
            .putString("model_${provider.name}", binding.actModel.text.toString())
            .putString("apikeys_${provider.name}", keys.joinToString("|||"))
            .putBoolean("supervised", binding.cbSupervised.isChecked)
            .putBoolean("autonomous", binding.cbAutonomous.isChecked)
            .putBoolean("live_mode", binding.cbLiveMode.isChecked)
            .apply()
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-MZ")
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            appendLog("⚠️ Reconhecimento de voz indisponível: ${e.message}")
        }
    }

    private fun onRunClicked() {
        saveCurrentValues()

        if (AgentAccessibilityService.instance == null) {
            appendLog("⚠️ Ative o serviço de acessibilidade primeiro.")
            return
        }

        val goal = binding.etCommand.text.toString().trim()
        if (goal.isEmpty()) {
            appendLog("⚠️ Digite ou fale o comando primeiro.")
            return
        }
        val keys = parseApiKeys()
        if (keys.isEmpty()) {
            appendLog("⚠️ Cole ao menos uma chave de API (uma por linha para várias).")
            return
        }
        if (keys.size > 1) {
            appendLog("🔑 ${keys.size} chaves detectadas — rotação automática em caso de limite (429).")
        }

        binding.tvLog.text = ""
        appendLog("🎯 Objetivo: $goal")

        val liveMode = binding.cbLiveMode.isChecked
        if (liveMode) {
            when {
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED -> {
                    appendLog("⚠️ Permissão de microfone não concedida — Modo Live sem áudio nesta execução.")
                }
                !voice.isSpeechRecognitionAvailable() -> {
                    appendLog("⚠️ Reconhecimento de voz contínuo indisponível neste aparelho.")
                }
                else -> {
                    voice.startContinuousListening { heard -> onVoiceHeardDuringTask(heard) }
                    appendLog("🔊 Modo Live ativado — o agente vai narrar os passos. Diga \"para\" a qualquer momento pra interromper.")
                }
            }
        }

        planner = TaskPlanner(
            scope = uiScope,
            onLog = { msg -> appendLog(msg) },
            onSpeak = { msg -> if (liveMode) voice.speak(msg) },
            onNeedsConfirmation = { action -> confirmAction(action, liveMode) },
            onFinished = {
                appendLog("🏁 Execução finalizada.")
                voice.stopListening()
            },
            skillManager = skillManager,
            memory = memory,
            onTaskCompleted = { finishedGoal, taskHistory ->
                runOnUiThread { offerToSaveSkill(finishedGoal, taskHistory) }
            }
        )

        planner?.run(
            goal = goal,
            provider = currentProvider(),
            apiKeys = parseApiKeys(),
            model = binding.actModel.text.toString().trim(),
            supervised = binding.cbSupervised.isChecked,
            autonomous = binding.cbAutonomous.isChecked
        )
    }

    /**
     * Mostra um diálogo de confirmação e suspende até o usuário responder —
     * por toque, ou por voz (dizendo "sim"/"não") quando o Modo Live está ativo.
     * O que responder primeiro decide; o outro caminho é ignorado.
     */
    private suspend fun confirmAction(action: LlmAction, liveMode: Boolean): Boolean {
        val channel = Channel<Boolean>(capacity = 1)
        val message = "Ação: ${action.action}\nAlvo: ${action.target ?: "-"}\nTexto: ${action.text ?: "-"}\nMotivo: ${action.reasoning ?: "-"}"
        AlertDialog.Builder(this)
            .setTitle("Confirmar ação do agente")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Permitir") { _, _ -> channel.trySend(true) }
            .setNegativeButton("Bloquear") { _, _ -> channel.trySend(false) }
            .show()

        if (liveMode) {
            pendingVoiceConfirmation = { heard ->
                when {
                    VoiceInteractionManager.matchesAny(heard, VoiceInteractionManager.YES_KEYWORDS) -> channel.trySend(true)
                    VoiceInteractionManager.matchesAny(heard, VoiceInteractionManager.NO_KEYWORDS) -> channel.trySend(false)
                }
                Unit
            }
            val pergunta = action.reasoning?.let { "Preciso da sua confirmação: $it. Posso continuar? Diga sim ou não." }
                ?: "Preciso da sua confirmação pra continuar. Diga sim ou não."
            voice.speak(pergunta)
        }

        val result = channel.receive()
        pendingVoiceConfirmation = null
        return result
    }

    /** Chamado toda vez que o Modo Live ouve uma frase durante uma tarefa em execução. */
    private fun onVoiceHeardDuringTask(heard: String) {
        appendLog("🎙️ Ouvido: \"$heard\"")

        val confirmationHandler = pendingVoiceConfirmation
        if (confirmationHandler != null) {
            confirmationHandler(heard)
            return
        }

        if (VoiceInteractionManager.matchesAny(heard, VoiceInteractionManager.STOP_KEYWORDS)) {
            planner?.stop()
            appendLog("⏹️ Parado por comando de voz.")
            voice.speak("Ok, parando.")
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread { binding.tvLog.append("$msg\n") }
    }

    // ---------- Ferramentas nativas: permissões ----------

    private fun requestNativeToolPermissions() {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(android.Manifest.permission.READ_CONTACTS)
        }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    // ---------- Skills: listar / apagar ----------

    private fun showSkillsDialog() {
        val skills = skillManager.listSkills()
        if (skills.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Skills salvas")
                .setMessage("Nenhuma skill salva ainda. Execute uma tarefa até o fim e o agente vai oferecer pra salvar o procedimento como skill reutilizável.")
                .setPositiveButton("OK", null)
                .setNeutralButton("➕ Criar manualmente") { _, _ -> showCreateSkillDialog() }
                .show()
            return
        }

        val labels: Array<CharSequence> = skills.map { "${it.name}  (gatilhos: ${it.triggers.joinToString(", ")})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Skills salvas (${skills.size})")
            .setItems(labels) { _: android.content.DialogInterface, index: Int -> showSkillDetailDialog(skills[index]) }
            .setNeutralButton("➕ Nova") { _, _ -> showCreateSkillDialog() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showSkillDetailDialog(skill: Skill) {
        AlertDialog.Builder(this)
            .setTitle(skill.name)
            .setMessage("Gatilhos: ${skill.triggers.joinToString(", ")}\n\nProcedimento:\n${skill.procedure}")
            .setPositiveButton("Fechar", null)
            .setNegativeButton("🗑️ Apagar") { _, _ ->
                skillManager.delete(skill)
                appendLog("🧩 Skill '${skill.name}' apagada.")
            }
            .show()
    }

    private fun showCreateSkillDialog(prefillName: String = "", prefillTriggers: String = "", prefillProcedure: String = "") {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Nome da skill (ex: Pedir Uber)"
            setText(prefillName)
        }
        val triggersInput = EditText(this).apply {
            hint = "Gatilhos separados por vírgula (ex: uber, corrida, táxi)"
            setText(prefillTriggers)
        }
        val procedureInput = EditText(this).apply {
            hint = "Procedimento passo a passo"
            minLines = 4
            maxLines = 10
            setText(prefillProcedure)
        }
        container.addView(TextView(this).apply { text = "Nome"; gravity = Gravity.START })
        container.addView(nameInput)
        container.addView(TextView(this).apply { text = "Gatilhos"; setPadding(0, 24, 0, 0) })
        container.addView(triggersInput)
        container.addView(TextView(this).apply { text = "Procedimento"; setPadding(0, 24, 0, 0) })
        container.addView(procedureInput)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Salvar skill")
            .setView(scroll)
            .setPositiveButton("Salvar") { _, _ ->
                val name = nameInput.text.toString().trim()
                val triggers = triggersInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val procedure = procedureInput.text.toString().trim()
                if (name.isEmpty() || procedure.isEmpty()) {
                    appendLog("⚠️ Skill não salva: nome e procedimento são obrigatórios.")
                    return@setPositiveButton
                }
                val skill = skillManager.save(name, triggers, procedure)
                appendLog("🧩 Skill '${skill.name}' salva (gatilhos: ${triggers.joinToString(", ")}).")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Chamado quando o TaskPlanner conclui uma tarefa com sucesso: oferece salvar como skill. */
    private fun offerToSaveSkill(goal: String, taskHistory: List<String>) {
        val autoProcedure = taskHistory.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
        val suggestedTriggers = goal.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 3 }
            .take(3)
            .joinToString(", ")

        AlertDialog.Builder(this)
            .setTitle("Salvar como skill?")
            .setMessage("A tarefa \"$goal\" foi concluída. Quer guardar esse procedimento como uma skill reutilizável, pra próxima vez o agente já saber os passos?")
            .setPositiveButton("Salvar") { _, _ ->
                showCreateSkillDialog(prefillName = goal, prefillTriggers = suggestedTriggers, prefillProcedure = autoProcedure)
            }
            .setNegativeButton("Não, obrigado", null)
            .show()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }
}
