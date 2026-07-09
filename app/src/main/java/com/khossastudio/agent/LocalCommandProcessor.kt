package com.khossastudio.agent

import android.content.Context
import com.khossastudio.agent.llm.LlmAction

class LocalCommandProcessor(private val context: Context) {

    fun process(heard: String): LlmAction? {
        val text = heard.lowercase().trim()

        // 1. Navegação
        if (matches(text, listOf("voltar", "back", "ir para trás"))) return LlmAction(action = "back", reasoning = "Navegação local")
        if (matches(text, listOf("home", "tela inicial", "ir para o início"))) return LlmAction(action = "home", reasoning = "Navegação local")

        // 2. Volume e Brilho
        if (matches(text, listOf("aumentar volume", "mais alto"))) return LlmAction(action = "adjust_volume", params = mapOf("direction" to "up"), reasoning = "Volume +")
        if (matches(text, listOf("diminuir volume", "mais baixo"))) return LlmAction(action = "adjust_volume", params = mapOf("direction" to "down"), reasoning = "Volume -")
        if (matches(text, listOf("silencioso", "mudo"))) return LlmAction(action = "adjust_volume", params = mapOf("direction" to "mute"), reasoning = "Mudo")
        if (matches(text, listOf("aumentar brilho", "mais brilho"))) return LlmAction(action = "adjust_brightness", params = mapOf("direction" to "up"), reasoning = "Brilho +")
        if (matches(text, listOf("diminuir brilho", "menos brilho"))) return LlmAction(action = "adjust_brightness", params = mapOf("direction" to "down"), reasoning = "Brilho -")

        // 3. Conectividade
        if (matches(text, listOf("ligar lanterna", "ativar lanterna"))) return LlmAction(action = "toggle_flashlight", params = mapOf("on" to "true"), reasoning = "Lanterna ON")
        if (matches(text, listOf("desligar lanterna", "apagar lanterna"))) return LlmAction(action = "toggle_flashlight", params = mapOf("on" to "false"), reasoning = "Lanterna OFF")
        if (matches(text, listOf("ligar wifi", "ativar wifi"))) return LlmAction(action = "toggle_wifi", params = mapOf("on" to "true"), reasoning = "Wi-Fi ON")
        if (matches(text, listOf("desligar wifi", "desativar wifi"))) return LlmAction(action = "toggle_wifi", params = mapOf("on" to "false"), reasoning = "Wi-Fi OFF")
        if (matches(text, listOf("ligar bluetooth", "ativar bluetooth"))) return LlmAction(action = "toggle_bluetooth", params = mapOf("on" to "true"), reasoning = "Bluetooth ON")
        if (matches(text, listOf("desligar bluetooth", "desativar bluetooth"))) return LlmAction(action = "toggle_bluetooth", params = mapOf("on" to "false"), reasoning = "Bluetooth OFF")

        // 4. Informações Offline
        if (matches(text, listOf("bateria", "carga", "energia"))) return LlmAction(action = "get_battery_info", reasoning = "Status da bateria")
        if (matches(text, listOf("que horas são", "qual a data", "que dia é hoje"))) return LlmAction(action = "get_time_info", reasoning = "Data e hora")

        // 5. Visão (Edge AI Vision)
        if (matches(text, listOf("o que é isto", "o que estás a ver", "descreve o ecrã"))) {
            return LlmAction(action = "analyze_screen", params = mapOf("mode" to "objects"), reasoning = "Visão local: Detetar objetos")
        }
        if (matches(text, listOf("lê isto", "lê o texto", "ocr"))) {
            return LlmAction(action = "analyze_screen", params = mapOf("mode" to "text"), reasoning = "Visão local: OCR")
        }

        // 6. Mídia
        if (matches(text, listOf("pausar", "parar música"))) return LlmAction(action = "control_music", params = mapOf("op" to "pause"), reasoning = "Mídia: Pausar")
        if (matches(text, listOf("tocar", "continuar música", "play"))) return LlmAction(action = "control_music", params = mapOf("op" to "play"), reasoning = "Mídia: Tocar")
        if (matches(text, listOf("próxima", "pular música"))) return LlmAction(action = "control_music", params = mapOf("op" to "next"), reasoning = "Mídia: Próxima")
        if (matches(text, listOf("anterior", "voltar música"))) return LlmAction(action = "control_music", params = mapOf("op" to "previous"), reasoning = "Mídia: Anterior")

        // 7. Abertura de Apps
        val app = detectApp(text)
        if (app != null) return LlmAction(action = "open_app", params = mapOf("name" to app), reasoning = "Abrir app: $app")

        return null
    }

    private fun matches(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun detectApp(text: String): String? {
        val prefixes = listOf("abrir ", "abre o ", "abre a ", "lançar ", "go to ")
        for (p in prefixes) {
            if (text.startsWith(p)) return text.removePrefix(p).trim()
        }
        return null
    }
}
