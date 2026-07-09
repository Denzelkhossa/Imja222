package com.khossastudio.agent.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Gerencia um conjunto de chaves de API para um mesmo provedor. Quando uma
 * chave recebe erro 429 (limite atingido), ela é colocada em "cooldown" por
 * um tempo, e a próxima chamada usa automaticamente a próxima chave livre.
 *
 * Round-robin entre as chaves disponíveis; se todas estiverem em cooldown,
 * usa a que estiver mais perto de liberar.
 */
class KeyRotationManager(rawKeys: List<String>) {

    private val keys: List<String> = rawKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var cursor = 0

    fun keyCount(): Int = keys.size

    fun hasKeys(): Boolean = keys.isNotEmpty()

    /** Retorna a próxima chave a usar (round-robin, pulando as em cooldown). */
    @Synchronized
    fun nextKey(): String? {
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val n = keys.size
        for (i in 0 until n) {
            val idx = (cursor + i) % n
            val candidate = keys[idx]
            if ((cooldownUntil[candidate] ?: 0L) <= now) {
                cursor = (idx + 1) % n
                return candidate
            }
        }
        // todas em cooldown: usa a que libera mais cedo (melhor esforço)
        return keys.minByOrNull { cooldownUntil[it] ?: 0L }
    }

    /** Marca uma chave como limitada; ela só volta a ser usada após cooldownSeconds. */
    fun markRateLimited(key: String, cooldownSeconds: Long) {
        cooldownUntil[key] = System.currentTimeMillis() + cooldownSeconds * 1000
    }

    /** Limpa o cooldown de uma chave que funcionou. */
    fun markSuccess(key: String) {
        cooldownUntil.remove(key)
    }

    /** Máscara curta para logs, nunca expõe a chave inteira. */
    fun mask(key: String): String =
        if (key.length > 8) key.take(4) + "…" + key.takeLast(4) else "****"

    /** Resumo do estado de todas as chaves, para diagnóstico na UI. */
    fun statusSummary(): String {
        if (keys.isEmpty()) return "sem chaves configuradas"
        val now = System.currentTimeMillis()
        return keys.joinToString(" | ") { k ->
            val until = cooldownUntil[k] ?: 0L
            val state = if (until > now) "cooldown ${(until - now) / 1000}s" else "ok"
            "${mask(k)}:$state"
        }
    }
}

/** Erro específico de limite de uso (HTTP 429) para permitir a rotação de chave. */
class RateLimitException(val retryAfterSeconds: Long?, message: String) : RuntimeException(message)

/**
 * Erro de chave inválida/expirada/sem permissão (401/403, ou 400 do tipo
 * "API_KEY_INVALID"). Tratado pelo LlmClient da mesma forma que RateLimitException:
 * em vez de abortar a tarefa, pula pra próxima chave da rotação.
 */
class AuthKeyException(message: String) : RuntimeException(message)
