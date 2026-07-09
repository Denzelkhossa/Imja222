package com.khossastudio.agent.llm

enum class LlmProvider(val displayName: String, val defaultModels: List<String>) {
    GROQ(
        "Groq",
        listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "deepseek-r1-distill-llama-70b",
            "gemma2-9b-it",
            "mixtral-8x7b-32768"
        )
    ),
    OPENAI(
        "OpenAI",
        listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o4-mini")
    ),
    CLAUDE(
        "Anthropic Claude",
        listOf("claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5-20251001")
    ),
    GEMINI(
        "Google Gemini",
        listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
    ),
    DEEPSEEK(
        "DeepSeek",
        listOf("deepseek-chat", "deepseek-reasoner")
    ),
    NVIDIA(
        "NVIDIA NIM",
        listOf("minimaxai/minimax-m2.7")
    );

    companion object {
        fun fromDisplayName(name: String): LlmProvider =
            values().firstOrNull { it.displayName == name } ?: GROQ
    }
}

/**
 * Ação decidida pelo modelo de IA para a tela atual.
 *
 * [params] é usado pelas ferramentas nativas (create_calendar_event, send_email,
 * search_contacts) que precisam de campos estruturados além de target/text —
 * por exemplo {"title": "...", "start": "...", "end": "..."}.
 */
data class LlmAction(
    val action: String,
    val target: String? = null,
    val text: String? = null,
    val reasoning: String? = null,
    val params: Map<String, String>? = null
)
