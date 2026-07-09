package com.khossastudio.agent.voice

/**
 * Deteta a palavra de ativação (item 1 do briefing: "Ativação por palavra-chave
 * ('Olá, Khossa')"). Não é reconhecimento de voz offline dedicado (tipo
 * Porcupine/Vosk) — usa o reconhecedor de fala contínuo do próprio Android
 * (ver [VoiceInteractionManager]) e casa o texto reconhecido contra variações
 * comuns da frase, para tolerar erros de transcrição ("olá cossa", "ok khossa",
 * "oi khossa" etc.).
 *
 * Nota de engenharia: isto significa que a deteção da wake word ainda depende
 * do reconhecedor de fala do Google (SpeechRecognizer), que normalmente exige
 * ligação à internet e não é 100% "always-on" com consumo mínimo como um motor
 * dedicado de wake-word. Para uma versão totalmente offline/mais eficiente,
 * o próximo passo natural é integrar um motor local (ex.: Vosk ou Porcupine)
 * — ver README > "Próximos passos sugeridos".
 */
object WakeWordDetector {

    private val WAKE_PHRASES = listOf(
        "olá khossa", "ola khossa", "oi khossa", "ok khossa", "e khossa",
        "olá kossa", "ola cossa", "oi cossa", "khossa,", "khossa "
    )

    /**
     * Retorna o texto do comando que vem DEPOIS da palavra de ativação, se a
     * frase ouvida contiver a wake word (ex.: "olá khossa, me lembra às 15h"
     * -> "me lembra às 15h"). Retorna null se a wake word não foi detetada.
     */
    fun extractCommandAfterWakeWord(heard: String): String? {
        val lower = heard.lowercase().trim()
        for (phrase in WAKE_PHRASES) {
            val idx = lower.indexOf(phrase.trim())
            if (idx != -1) {
                val after = heard.substring(idx + phrase.trim().length)
                    .trim()
                    .trimStart(',', '.', '!', '?', ' ')
                return after
            }
        }
        return null
    }

    fun containsWakeWord(heard: String): Boolean = extractCommandAfterWakeWord(heard) != null
}
