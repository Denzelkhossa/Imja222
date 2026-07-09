package com.khossastudio.agent

import android.graphics.Rect
import com.khossastudio.agent.llm.LlmAction
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.tools.AndroidControlTools
import com.khossastudio.agent.tools.NativeTools
import com.khossastudio.agent.tools.ReminderTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lista de trechos de nome de pacote considerados sensíveis (bancos, carteiras,
 * pagamentos). Ações nesses apps exigem sempre confirmação manual, mesmo com o
 * modo supervisão desligado.
 */
private val SENSITIVE_PACKAGE_HINTS = listOf(
    "bank", "banco", "bci", "bim", "absa", "standardbank", "mpesa", "emola",
    "pay", "wallet", "wise", "paypal", "visa", "mastercard"
)

/**
 * Ações que pedem SEMPRE confirmação manual — mesmo com "modo supervisão" e
 * "modo autónomo" ligados. Regra de segurança do briefing: "nunca enviar
 * mensagens ou apagar arquivos sem autorização".
 */
private val HARD_CONFIRM_ACTIONS = setOf("send_message")

/**
 * Ações consideradas sensíveis mas não "duras": pedem confirmação a não ser
 * que o modo autónomo esteja ligado (ligar, alarmes, notificações).
 */
private val SOFT_SENSITIVE_ACTIONS = setOf("open_dialer", "create_alarm")

object ActionExecutor {

    /** Retorna true se o pacote atual da tela parece sensível (financeiro). */
    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName == null) return false
        val lower = packageName.lowercase()
        return SENSITIVE_PACKAGE_HINTS.any { lower.contains(it) }
    }

    fun isHardConfirm(action: LlmAction): Boolean = action.action in HARD_CONFIRM_ACTIONS

    /**
     * Decide se a ação precisa de confirmação do usuário antes de ser executada.
     * - `supervised` = true: confirma TUDO (modo supervisão).
     * - Ações em [HARD_CONFIRM_ACTIONS] ou em apps sensíveis (financeiro):
     *   confirma sempre, mesmo em modo autónomo.
     * - [SOFT_SENSITIVE_ACTIONS]: confirma a não ser que `autonomous` esteja ligado.
     */
    fun needsConfirmation(action: LlmAction, currentPackage: String?, supervised: Boolean, autonomous: Boolean): Boolean {
        if (supervised) return true
        if (isHardConfirm(action)) return true
        if (isSensitivePackage(currentPackage)) return true
        if (action.action in SOFT_SENSITIVE_ACTIONS && !autonomous) return true
        return false
    }

    /**
     * Executa a ação. Retorna uma mensagem de log descrevendo o resultado.
     * `suspend` porque as ferramentas de memória (Room) e algumas de I/O correm
     * fora da main thread; as ações de acessibilidade (clique, scroll...)
     * continuam a rodar na main thread, como sempre exigido pela AccessibilityNodeInfo.
     */
    suspend fun execute(service: AgentAccessibilityService, action: LlmAction, memory: MemoryManager? = null): String {
        return when (action.action) {
            // ---- Ações na árvore de UI: sempre na main thread (AccessibilityNodeInfo). ----
            "click", "long_click", "type", "scroll", "back", "home" -> withContext(Dispatchers.Main) {
                executeScreenAction(service, action)
            }
            "wait" -> "Aguardando..."
            "complete" -> "Tarefa concluída"

            // ---- Ferramentas nativas: agem direto no sistema, sem passar pela tela. ----
            "create_calendar_event" -> NativeTools.createCalendarEvent(service, action.params)
            "send_email" -> NativeTools.sendEmail(service, action.params)
            "search_contacts" -> NativeTools.searchContacts(service, action.params)
            "open_camera" -> NativeTools.openCamera(service)

            // ---- Lembretes e alarmes ----
            "create_reminder" -> ReminderTools.createReminder(service, action.params)
            "create_alarm" -> AndroidControlTools.createAlarm(service, action.params)

            // ---- Chamadas e mensagens (sempre pré-preenchidas, nunca automáticas) ----
            "open_dialer" -> AndroidControlTools.openDialer(service, action.params)
            "send_message" -> AndroidControlTools.openMessage(service, action.params)

            // ---- Media, apps e notificações ----
            "control_music" -> AndroidControlTools.MediaControlTool.control(service, action.params?.get("op") ?: "")
            "open_app" -> AndroidControlTools.openApp(service, action.params)
            "read_notifications" -> AndroidControlTools.readNotifications(service, action.params)
            "toggle_flashlight" -> AndroidControlTools.toggleFlashlight(service, action.params)
            "toggle_wifi" -> AndroidControlTools.toggleWifi(service, action.params)
            "toggle_bluetooth" -> AndroidControlTools.toggleBluetooth(service, action.params)
            "adjust_volume" -> AndroidControlTools.adjustVolume(service, action.params)
            "adjust_brightness" -> AndroidControlTools.adjustBrightness(service, action.params)
            "get_battery_info" -> AndroidControlTools.getBatteryInfo(service)
            "get_time_info" -> AndroidControlTools.getLocalTimeInfo()
            "toggle_power_save" -> AndroidControlTools.togglePowerSave(service, action.params)
            "analyze_screen" -> AndroidControlTools.analyzeScreen(service, action.params)
            "analyze_camera" -> AndroidControlTools.analyzeCamera(service, action.params)

            // ---- Memória local (Room): contactos e preferências ----
            "remember_contact" -> {
                if (memory == null) "⚠️ Memória indisponível"
                else {
                    val name = action.params?.get("name")
                    if (name == null) "❌ Faltou o nome"
                    else {
                        memory.rememberContact(name, action.params?.get("phone"), action.params?.get("relationship"))
                        "✅ Contacto guardado: $name"
                    }
                }
            }
            "remember_preference" -> memory?.let {
                val key = action.params?.get("key")
                val value = action.params?.get("value")
                if (key == null || value == null) "❌ Faltou key/value" else {
                    it.rememberPreference(key, value)
                    "✅ Preferência guardada"
                }
            } ?: "⚠️ Memória indisponível"
            "recall_preference" -> memory?.let {
                val key = action.params?.get("key") ?: return@let "❌ Faltou params.key"
                val value = it.recallPreference(key) ?: "não encontrado"
                "🧠 ${key} = ${value}"
            } ?: "⚠️ Memória indisponível"

            else -> "Ação desconhecida: ${action.action}"
        }
    }

    private fun executeScreenAction(service: AgentAccessibilityService, action: LlmAction): String {
        return when (action.action) {
            "click" -> {
                val node = resolveTarget(service, action.target)
                if (node != null) {
                    val ok = service.clickNode(node)
                    "Clique em '${action.target}': ${if (ok) "OK" else "falhou"}"
                } else if (action.target?.startsWith("bounds:") == true) {
                    val rect = parseBounds(action.target)
                    if (rect != null) {
                        val ok = service.clickAtBounds(rect)
                        "Clique via gesto em bounds: ${if (ok) "OK" else "falhou"}"
                    } else "Não foi possível interpretar bounds: ${action.target}"
                } else {
                    "Elemento não encontrado: ${action.target}"
                }
            }
            "long_click" -> {
                val node = resolveTarget(service, action.target)
                if (node != null) {
                    val ok = service.longClickNode(node)
                    "Toque longo em '${action.target}': ${if (ok) "OK" else "falhou"}"
                } else "Elemento não encontrado: ${action.target}"
            }
            "type" -> {
                val node = resolveTarget(service, action.target)
                if (node != null && action.text != null) {
                    val ok = service.setTextOnNode(node, action.text)
                    "Digitou '${action.text}' em '${action.target}': ${if (ok) "OK" else "falhou"}"
                } else "Não foi possível digitar: elemento ou texto ausente"
            }
            "scroll" -> {
                val node = resolveTarget(service, action.target)
                if (node != null) {
                    val ok = service.scrollNode(node, true)
                    "Scroll em '${action.target}': ${if (ok) "OK" else "falhou"}"
                } else {
                    "Elemento rolável não encontrado, tentando tela toda"
                }
            }
            "back" -> {
                service.pressBack()
                "Voltar (back) executado"
            }
            "home" -> {
                service.pressHome()
                "Home executado"
            }
            else -> "Ação de tela desconhecida: ${action.action}"
        }
    }

    private fun resolveTarget(service: AgentAccessibilityService, target: String?): android.view.accessibility.AccessibilityNodeInfo? {
        if (target == null) return null
        return when {
            target.startsWith("text:") -> service.findNodeByText(target.removePrefix("text:"))
            target.startsWith("id:") -> service.findNodeById(target.removePrefix("id:"))
            else -> null
        }
    }

    private fun parseBounds(target: String): Rect? {
        return try {
            val inner = target.removePrefix("bounds:[").removeSuffix("]")
            val parts = inner.split(",").map { it.trim().toInt() }
            Rect(parts[0], parts[1], parts[2], parts[3])
        } catch (e: Exception) {
            null
        }
    }
}
