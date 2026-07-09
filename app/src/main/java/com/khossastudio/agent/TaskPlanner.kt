package com.khossastudio.agent

import com.khossastudio.agent.llm.KeyRotationManager
import com.khossastudio.agent.llm.LlmAction
import com.khossastudio.agent.llm.LlmClient
import com.khossastudio.agent.llm.LlmProvider
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.memory.ContactMemory
import com.khossastudio.agent.skills.SkillManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orquestra o ciclo: capturar tela -> perguntar ao LLM -> executar ação -> repetir.
 * É o "cérebro" descrito no item 2 do briefing: entende linguagem natural,
 * decompõe o objetivo em passos (um por iteração do loop) e escolhe a
 * ferramenta certa a cada passo — de forma nativa (calendário, lembrete,
 * mensagem) sempre que possível, ou navegando a tela via Acessibilidade
 * quando não há ferramenta nativa para a tarefa.
 *
 * onLog: callback para mostrar mensagens técnicas na UI (sempre chamado na main thread).
 * onSpeak: callback opcional para o Modo Live — recebe frases curtas em linguagem
 * natural (não o log técnico completo) pra o app narrar por voz enquanto trabalha.
 * onNeedsConfirmation: chamado quando a ação precisa de aprovação do usuário
 * (ver `ActionExecutor.needsConfirmation` para as regras exatas);
 * deve retornar true/false conforme o usuário aprova a ação.
 */
class TaskPlanner(
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val onNeedsConfirmation: suspend (LlmAction) -> Boolean,
    private val onFinished: () -> Unit,
    private val skillManager: SkillManager? = null,
    private val memory: MemoryManager? = null,
    private val onTaskCompleted: ((goal: String, history: List<String>) -> Unit)? = null,
    private val onSpeak: ((String) -> Unit)? = null
) {
    private var running = false
    private val maxSteps = 25
    private val history = mutableListOf<String>()

    fun stop() {
        running = false
    }

    /**
     * [autonomous] = "modo autónomo" (item 6 do briefing): quando ligado, ações
     * sensíveis "leves" (ligar, alarme) não pedem confirmação — mas ações
     * "duras" (mandar mensagem, apps financeiros) continuam a pedir sempre,
     * e "modo supervisão" continua a mandar em tudo se estiver ligado.
     */
    fun run(
        goal: String,
        provider: LlmProvider,
        apiKeys: List<String>,
        model: String,
        supervised: Boolean,
        autonomous: Boolean = false
    ) {
        val keyManager = KeyRotationManager(apiKeys)
        if (!keyManager.hasKeys()) {
            onLog("⚠️ Configure ao menos uma chave de API antes de executar.")
            onFinished()
            return
        }
        if (keyManager.keyCount() > 1) {
            onLog("🔑 ${keyManager.keyCount()} chaves carregadas para $provider — rotação automática ativa.")
        }

        running = true
        history.clear()
        onSpeak?.invoke("Começando: $goal")

        val relevantSkills = skillManager?.findRelevant(goal) ?: emptyList()
        if (relevantSkills.isNotEmpty()) {
            onLog("🧩 Usando skill(s) salva(s): ${relevantSkills.joinToString(", ") { it.name }}")
        }

        scope.launch {
            var steps = 0
            var finalStatus = "cancelada"
            try {
                val memoryContext = buildMemoryContext(goal)
                if (!memoryContext.isNullOrBlank()) {
                    onLog("🧠 Memória relevante encontrada e incluída no contexto.")
                }

                while (running && steps < maxSteps) {
                    steps++
                    val service = AgentAccessibilityService.instance
                    if (service == null) {
                        onLog("⚠️ Serviço de acessibilidade não está ativo. Ative-o nas configurações.")
                        onSpeak?.invoke("Preciso que você ative o serviço de acessibilidade pra eu continuar.")
                        finalStatus = "falhou"
                        break
                    }

                    val screenJson = withContext(Dispatchers.Default) { service.getCurrentScreenJson() }
                    onLog("📱 Tela capturada (${screenJson.optJSONArray("elements")?.length() ?: 0} elementos)")

                    val action: LlmAction
                    try {
                        action = withContext(Dispatchers.IO) {
                            LlmClient.getNextAction(
                                provider, keyManager, model, screenJson, history, goal,
                                skills = relevantSkills,
                                memoryContext = memoryContext,
                                onKeyEvent = { msg -> onLog(msg) }
                            )
                        }
                    } catch (e: Exception) {
                        onLog("❌ Erro ao consultar IA (todas as chaves esgotadas ou erro real): ${e.message}")
                        onSpeak?.invoke("Tive um problema pra consultar a inteligência artificial.")
                        finalStatus = "falhou"
                        break
                    }

                    onLog("🤖 Ação decidida: ${action.action} target=${action.target} texto=${action.text}")
                    action.reasoning?.let { onLog("   Motivo: $it") }

                    if (action.action == "complete") {
                        onLog("✅ Tarefa concluída pelo agente.")
                        onSpeak?.invoke("Pronto! Tarefa concluída.")
                        finalStatus = "concluida"
                        if (history.isNotEmpty()) onTaskCompleted?.invoke(goal, history.toList())
                        break
                    }

                    onSpeak?.invoke(action.reasoning ?: describeActionForSpeech(action))

                    val packageName = service.rootInActiveWindow?.packageName?.toString()
                    val needsConfirm = ActionExecutor.needsConfirmation(action, packageName, supervised, autonomous)
                    if (needsConfirm) {
                        val approved = onNeedsConfirmation(action)
                        if (!approved) {
                            onLog("⏸️ Ação cancelada pelo usuário.")
                            onSpeak?.invoke("Tudo bem, parando por aqui.")
                            finalStatus = "cancelada"
                            break
                        }
                    }

                    val resultMsg = ActionExecutor.execute(service, action, memory)
                    onLog("➡️ $resultMsg")
                    history.add("${action.action} ${action.target ?: ""} -> $resultMsg")

                    delay(1200) // aguarda a tela reagir/carregar
                }

                if (steps >= maxSteps) {
                    onLog("⚠️ Limite de passos atingido ($maxSteps). Parando por segurança.")
                    onSpeak?.invoke("Atingi o limite de passos, vou parar por segurança.")
                    finalStatus = "falhou"
                }
            } finally {
                memory?.logTask("$goal: $finalStatus")
                running = false
                onFinished()
            }
        }
    }

    /**
     * Monta um resumo curto de contactos/preferências guardados que pareçam
     * relevantes para o objetivo (por nome/relação mencionados no texto), para
     * o LLM não precisar perguntar de novo "quem é a tua mãe" a cada tarefa.
     */
    private suspend fun buildMemoryContext(goal: String): String? {
        val repo = memory ?: return null
        val lowerGoal = goal.lowercase()

        val contacts = repo.allContacts().filter { c ->
            lowerGoal.contains(c.name.lowercase()) || (c.relationship?.let { lowerGoal.contains(it.lowercase()) } == true)
        }
        val prefs = repo.allPreferences()

        if (contacts.isEmpty() && prefs.isEmpty()) return null

        return buildString {
            if (contacts.isNotEmpty()) {
                append("Contactos: ")
                append(contacts.joinToString("; ") { c ->
                    "${c.name}${c.relationship?.let { " ($it)" } ?: ""}${c.phone?.let { " - $it" } ?: ""}"
                })
                append("\n")
            }
            if (prefs.isNotEmpty()) {
                append("Preferências: ")
                append(prefs.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }.trim()
    }

    /** Frase curta em linguagem natural pra narrar a ação no Modo Live, quando o LLM não deu "reasoning". */
    private fun describeActionForSpeech(action: LlmAction): String {
        val alvo = action.target
            ?.removePrefix("text:")
            ?.removePrefix("id:")
            ?.removePrefix("bounds:")
        return when (action.action) {
            "click" -> "Vou tocar em ${alvo ?: "um elemento"}"
            "long_click" -> "Vou manter pressionado em ${alvo ?: "um elemento"}"
            "type" -> "Vou digitar \"${action.text ?: ""}\""
            "scroll" -> "Vou rolar a tela"
            "back" -> "Vou voltar"
            "home" -> "Vou pra tela inicial"
            "wait" -> "Vou aguardar um pouco"
            "create_calendar_event" -> "Vou criar um evento no calendário"
            "send_email" -> "Vou abrir o e-mail"
            "search_contacts" -> "Vou procurar o contato"
            "open_camera" -> "Vou abrir a câmera"
            "create_reminder" -> "Vou criar um lembrete"
            "create_alarm" -> "Vou criar um alarme"
            "open_dialer" -> "Vou abrir o discador"
            "send_message" -> "Vou preparar a mensagem"
            "control_music" -> "Vou controlar a música"
            "open_app" -> "Vou abrir o app"
            "read_notifications" -> "Vou ver as notificações recentes"
            "remember_contact" -> "Vou guardar esse contacto na memória"
            "remember_preference" -> "Vou guardar essa preferência"
            "recall_preference" -> "Vou verificar o que já sei sobre isso"
            else -> "Próximo passo"
        }
    }
}
