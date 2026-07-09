package com.khossastudio.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Controla o "Modo Live": o agente narra em voz alta o que está fazendo (TTS)
 * enquanto executa a tarefa, e fica ouvindo continuamente (STT) pra permitir
 * o usuário interromper ("para", "cancela") ou responder confirmações
 * faladas — sem precisar olhar pro telefone.
 *
 * Pausa a escuta automaticamente enquanto fala, pra o microfone não captar
 * a própria voz do agente.
 *
 * Observação: usa o reconhecedor de fala do sistema em modo "uma frase por
 * vez, reiniciado automaticamente" (não há streaming contínuo nativo estável
 * em todas as versões do Android), então há uma pequena pausa entre frases —
 * suficiente pra decidir se o usuário quer interromper ou confirmar algo.
 */
class VoiceInteractionManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var shouldKeepListening = false
    private var onHeard: ((String) -> Unit)? = null
    private val pendingSpeechCallbacks = mutableMapOf<String, () -> Unit>()

    /**
     * Nível de volume do microfone em tempo real, já normalizado para 0f
     * (silêncio) .. 1f (alto) — alimenta a ondulação/orbe estilo Siri no
     * ecrã do assistente (ver KhossaAssistantScreen.SiriOrb).
     */
    var onLevel: ((Float) -> Unit)? = null

    /** Inicializa o TTS. [onReady] informa se ficou disponível. */
    fun init(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val result = tts?.setLanguage(Locale("pt", "BR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pendingSpeechCallbacks.remove(it) }?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pendingSpeechCallbacks.remove(it) }?.invoke()
                    }
                })
            }
            onReady(ttsReady)
        }
    }

    fun isSpeechRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Fala um texto. [onDone] roda quando termina de falar (ou se o TTS não estiver pronto). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (text.isBlank() || engine == null || !ttsReady) {
            onDone?.invoke()
            return
        }
        val wasListening = shouldKeepListening
        if (wasListening) pauseListening()

        val id = UUID.randomUUID().toString()
        pendingSpeechCallbacks[id] = {
            onDone?.invoke()
            if (wasListening) resumeListening()
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stopSpeaking() {
        tts?.stop()
        pendingSpeechCallbacks.clear()
    }

    /**
     * Começa a escutar continuamente. Cada frase reconhecida chega em [onResult].
     * Reinicia sozinho após cada resultado/erro (silêncio, timeout etc.), até
     * [stopListening] ser chamado.
     */
    fun startContinuousListening(onResult: (String) -> Unit) {
        if (!isSpeechRecognitionAvailable()) return
        onHeard = onResult
        shouldKeepListening = true
        resumeListening()
    }

    fun startListening(onResult: (String) -> Unit) = startContinuousListening(onResult)

    fun stopListening() {
        shouldKeepListening = false
        pauseListening()
    }

    private fun pauseListening() {
        recognizer?.stopListening()
        recognizer?.cancel()
        onLevel?.invoke(0f)
    }

    private fun resumeListening() {
        if (!shouldKeepListening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-MZ")
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onHeard?.invoke(text)
                resumeListening()
            }
            override fun onError(error: Int) {
                // Erros comuns aqui são silêncio/timeout — apenas volta a escutar.
                resumeListening()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Normaliza o valor bruto do reconhecedor (aprox. 0..10) para
                // 0f..1f, usado para animar a ondulação do orbe em tempo real.
                onLevel?.invoke(((rmsdB - 1.5f) / 8.5f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(intent)
    }

    fun shutdown() {
        stopListening()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        pendingSpeechCallbacks.clear()
    }

    companion object {
        /** Ouvidas durante uma tarefa em execução, param o agente. */
        val STOP_KEYWORDS = listOf("para", "pare", "parar", "cancela", "cancelar", "chega", "stop")

        /** Respostas afirmativas a uma confirmação falada. */
        val YES_KEYWORDS = listOf("sim", "pode", "permite", "permitir", "confirma", "confirmar", "certo", "ok")

        /** Respostas negativas a uma confirmação falada. */
        val NO_KEYWORDS = listOf("não", "nao", "bloqueia", "bloquear", "negativo", "espera")

        fun matchesAny(heard: String, keywords: List<String>): Boolean {
            val lower = heard.lowercase()
            return keywords.any { lower.contains(it) }
        }
    }
}
