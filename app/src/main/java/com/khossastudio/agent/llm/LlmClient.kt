package com.khossastudio.agent.llm

import com.khossastudio.agent.skills.Skill
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LlmClient {

    const val SYSTEM_PROMPT = """
Você é um agente de IA que controla um telefone Android através da API de Acessibilidade
e de um conjunto de ferramentas nativas do sistema.
Sua missão é completar tarefas para o usuário interagindo com os aplicativos instalados.

VOCÊ DEVE:
- Analisar a árvore de UI da tela atual (fornecida em JSON)
- Decidir qual é a PRÓXIMA ação necessária para avançar em direção ao objetivo
- Responder APENAS com um JSON válido, sem markdown, sem texto antes ou depois

FORMATO DE RESPOSTA (JSON puro):
{
  "action": "click" | "long_click" | "type" | "scroll" | "back" | "home" | "wait" | "complete"
           | "create_calendar_event" | "send_email" | "search_contacts" | "open_camera"
           | "create_reminder" | "create_alarm" | "open_dialer" | "send_message"
           | "control_music" | "open_app" | "read_notifications"
           | "remember_contact" | "remember_preference" | "recall_preference",
  "target": "text:Enviar" | "id:com.whatsapp:id/send" | "bounds:[100,200,300,400]",
  "text": "texto a digitar (apenas se action for type)",
  "params": { "campo": "valor" },
  "reasoning": "breve explicação"
}

FERRAMENTAS NATIVAS (não navegam pela tela, agem direto no sistema — prefira-as
sempre que a tarefa se encaixar, são mais confiáveis que clicar em apps):
- create_calendar_event: params={title, description?, location?, start, end?}
  (start/end no formato "yyyy-MM-dd HH:mm"). Abre o Calendário com o evento
  pronto para o usuário confirmar.
- send_email: params={to?, subject?, body?}. Abre o app de e-mail já preenchido.
- search_contacts: params={query}. Procura direto na agenda do telefone e
  retorna nome+telefone encontrados, sem abrir nenhum app.
- open_camera: sem params. Abre a câmera para o usuário fotografar.
- create_reminder: params={message, at}. Lembrete simples numa hora exata
  ("15:00" ou "yyyy-MM-dd HH:mm") — usa isto para "me lembra às X", não
  create_calendar_event (que é para eventos com duração/local).
- create_alarm: params={hour, minute, message?}. Abre o Relógio com um alarme
  pronto — usa quando o usuário disser literalmente "alarme".
- open_dialer: params={number}. Abre o discador com o número pronto; o
  usuário é quem toca em ligar (o agente nunca liga sozinho).
- send_message: params={number, text, app? ("sms"|"whatsapp")}. Abre a
  mensagem já escrita; o usuário é quem toca em enviar (o agente nunca
  envia sozinho — esta ação SEMPRE pede confirmação, mesmo em modo autônomo).
- control_music: params={op: "play_pause"|"next"|"previous"|"stop"}.
- open_app: params={name}. Abre um app pelo nome visível (ex.: "WhatsApp").
- read_notifications: params={app?, limit?}. Lê as últimas notificações
  recebidas (requer permissão de acesso a notificações).
- remember_contact: params={name, relationship?, phone?, notes?}. Guarda na
  memória do agente quem é alguém (ex.: name="mãe", phone="8XX..."), para
  usar depois em vez de perguntar de novo ou buscar na agenda.
- remember_preference / recall_preference: params={key, value?}. Guarda ou
  relê uma preferência do usuário (ex.: key="hora_dormir", value="22:30").
Depois de usar uma ferramenta nativa, se ela já resolveu a tarefa, responda
"complete" no passo seguinte.

DECOMPOSIÇÃO DE TAREFAS COM VÁRIOS PASSOS: quando o objetivo tiver mais de uma
parte (ex.: "me lembra de sair às 15h e manda mensagem para minha mãe"),
resolva um passo de cada vez, nesta ordem natural: (1) o que faltar descobrir
primeiro (ex.: procurar o contato com search_contacts ou remember_contact se
já souber quem é "mãe"), (2) a ação que não precisa de confirmação do
usuário (ex.: create_reminder), (3) por último a que precisa (ex.:
send_message) — assim, se o usuário cancelar no fim, o que já foi feito
(o lembrete) continua válido.

REGRAS:
- Se a tarefa já foi concluída, responda com "action": "complete"
- Se a tela não contém o elemento esperado, considere "scroll" ou "back"
- Priorize localizar elementos por texto visível, depois por id
- Se houver um pop-up inesperado, tente fechá-lo antes de continuar
- Se um "PROCEDIMENTO CONHECIDO" for fornecido abaixo, siga-o como roteiro
  preferencial, adaptando aos elementos realmente presentes na tela atual
- Se houver "MEMÓRIA CONHECIDA" fornecida abaixo (contatos/preferências já
  guardados), use-a em vez de perguntar de novo ou de chamar search_contacts
"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Cooldown padrão (segundos) quando o provedor não informa Retry-After. */
    private const val DEFAULT_COOLDOWN_SECONDS = 60L

    /** Cooldown (segundos) para uma chave que parece inválida — não compensa tentar de novo cedo. */
    private const val INVALID_KEY_COOLDOWN_SECONDS = 3600L

    /**
     * Pede ao modelo a próxima ação. Executa de forma síncrona - chamar sempre
     * a partir de uma coroutine/thread de fundo (nunca na main thread).
     *
     * Usa o [keyManager] para rodar automaticamente entre as chaves configuradas:
     * se uma chave bater no limite (HTTP 429), ela entra em cooldown e a próxima
     * chamada tenta a chave seguinte, até esgotar todas as chaves disponíveis.
     *
     * onKeyEvent (opcional) recebe mensagens de log sobre rotação de chaves,
     * para você exibir na UI/console.
     */
    fun getNextAction(
        provider: LlmProvider,
        keyManager: KeyRotationManager,
        model: String,
        screenJson: JSONObject,
        history: List<String>,
        goal: String,
        skills: List<Skill> = emptyList(),
        memoryContext: String? = null,
        onKeyEvent: ((String) -> Unit)? = null
    ): LlmAction {
        if (!keyManager.hasKeys()) {
            throw RuntimeException("Nenhuma chave de API configurada para $provider")
        }

        val userContent = buildString {
            append("OBJETIVO: ").append(goal).append("\n\n")
            if (!memoryContext.isNullOrBlank()) {
                append("MEMÓRIA CONHECIDA (contatos e preferências já guardados):\n")
                append(memoryContext).append("\n\n")
            }
            if (skills.isNotEmpty()) {
                append("PROCEDIMENTO(S) CONHECIDO(S) (skills salvas relevantes para este objetivo):\n")
                skills.forEach { skill ->
                    append("### ").append(skill.name).append("\n")
                    append(skill.procedure).append("\n\n")
                }
            }
            append("HISTÓRICO DE AÇÕES:\n")
            if (history.isEmpty()) append("(nenhuma ainda)\n")
            history.forEach { append("- ").append(it).append("\n") }
            append("\nESTADO ATUAL DA TELA (JSON):\n")
            append(screenJson.toString())
        }

        val maxAttempts = keyManager.keyCount()
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            val key = keyManager.nextKey() ?: break
            try {
                val rawText = when (provider) {
                    LlmProvider.GROQ -> callOpenAiCompatible(
                        "https://api.groq.com/openai/v1/chat/completions", key, model, userContent
                    )
                    LlmProvider.OPENAI -> callOpenAiCompatible(
                        "https://api.openai.com/v1/chat/completions", key, model, userContent
                    )
                    LlmProvider.DEEPSEEK -> callOpenAiCompatible(
                        "https://api.deepseek.com/chat/completions", key, model, userContent
                    )
                    LlmProvider.NVIDIA -> callOpenAiCompatible(
                        "https://integrate.api.nvidia.com/v1/chat/completions", key, model, userContent
                    )
                    LlmProvider.CLAUDE -> callClaude(key, model, userContent)
                    LlmProvider.GEMINI -> callGemini(key, model, userContent)
                }
                keyManager.markSuccess(key)
                return parseAction(rawText)
            } catch (e: RateLimitException) {
                val cooldown = e.retryAfterSeconds ?: DEFAULT_COOLDOWN_SECONDS
                keyManager.markRateLimited(key, cooldown)
                onKeyEvent?.invoke("⏳ Chave ${keyManager.mask(key)} atingiu o limite (429). Rodando para a próxima (tentativa ${attempt + 1}/$maxAttempts).")
                lastError = e
            } catch (e: AuthKeyException) {
                // Chave provavelmente inválida/expirada/mal formatada: cooldown longo
                // (não vale a pena testar de novo tão cedo) e passa pra próxima.
                keyManager.markRateLimited(key, INVALID_KEY_COOLDOWN_SECONDS)
                onKeyEvent?.invoke("🚫 Chave ${keyManager.mask(key)} parece inválida/sem permissão (${e.message}). Rodando para a próxima (tentativa ${attempt + 1}/$maxAttempts).")
                lastError = e
            }
        }

        throw lastError ?: RuntimeException("Todas as $maxAttempts chaves de $provider estão em cooldown. ${keyManager.statusSummary()}")
    }

    // ---------- OpenAI / Groq / DeepSeek (formato compatível) ----------

    private fun callOpenAiCompatible(url: String, apiKey: String, model: String, userContent: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            messages.put(JSONObject().put("role", "user").put("content", userContent))
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (resp.code == 429) {
                throw RateLimitException(retryAfterFromResponse(resp), "Rate limit: $bodyStr")
            }
            checkAuthError(resp.code, bodyStr)
            if (!resp.isSuccessful) throw RuntimeException("Erro API (${resp.code}): $bodyStr")
            val json = JSONObject(bodyStr)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    // ---------- Anthropic Claude ----------

    private fun callClaude(apiKey: String, model: String, userContent: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1000)
            put("system", SYSTEM_PROMPT)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "user").put("content", userContent))
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (resp.code == 429) {
                throw RateLimitException(retryAfterFromResponse(resp), "Rate limit: $bodyStr")
            }
            checkAuthError(resp.code, bodyStr)
            if (!resp.isSuccessful) throw RuntimeException("Erro API (${resp.code}): $bodyStr")
            val json = JSONObject(bodyStr)
            val content = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return sb.toString()
        }
    }

    // ---------- Google Gemini ----------

    private fun callGemini(apiKey: String, model: String, userContent: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            })
            val contents = JSONArray()
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userContent)))
            })
            put("contents", contents)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (resp.code == 429) {
                throw RateLimitException(retryAfterFromResponse(resp), "Rate limit: $bodyStr")
            }
            checkAuthError(resp.code, bodyStr)
            if (!resp.isSuccessful) throw RuntimeException("Erro API (${resp.code}): $bodyStr")
            val json = JSONObject(bodyStr)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    /** Lê o header Retry-After (segundos) se o provedor mandar; senão retorna null. */
    private fun retryAfterFromResponse(resp: Response): Long? {
        return resp.header("Retry-After")?.toLongOrNull()
            ?: resp.header("retry-after")?.toLongOrNull()
    }

    /**
     * Se a resposta indicar chave inválida/sem permissão (401/403, ou 400 com uma
     * das mensagens típicas do Gemini), lança [AuthKeyException] para o chamador
     * rodar pra próxima chave em vez de parar a tarefa inteira. Isto importa
     * especialmente para o Gemini: chaves reais da API (Google AI Studio) sempre
     * começam por "AIzaSy...". Uma chave com outro formato tende a cair aqui
     * como erro 400/401/403 em vez de 429 — por isso a rotação também cobre
     * estes códigos, não só limite de uso.
     */
    private fun checkAuthError(code: Int, bodyStr: String) {
        val lower = bodyStr.lowercase()
        val looksLikeAuthProblem = code == 401 || code == 403 ||
            (code == 400 && (lower.contains("api_key_invalid") || lower.contains("api key not valid"))) ||
            lower.contains("permission_denied") || lower.contains("unauthenticated")
        if (looksLikeAuthProblem) {
            throw AuthKeyException("HTTP $code: chave rejeitada pelo provedor")
        }
    }

    // ---------- Parsing da resposta ----------

    private fun parseAction(raw: String): LlmAction {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Se o modelo mandar texto extra, tenta extrair o primeiro objeto JSON
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val jsonStr = if (start != -1 && end != -1 && end > start) cleaned.substring(start, end + 1) else cleaned

        val json = JSONObject(jsonStr)
        val paramsObj = json.optJSONObject("params")
        val params = paramsObj?.let { obj ->
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key) }
            map
        }
        return LlmAction(
            action = json.optString("action", "wait"),
            target = json.optString("target", null),
            text = json.optString("text", null),
            reasoning = json.optString("reasoning", null),
            params = params
        )
    }
}
