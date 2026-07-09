package com.khossastudio.agent.voice

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Gestor de Wake Word Offline usando Vosk.
 * Permite detecção de "Hey Khossa" sem internet e com baixo consumo.
 */
class WakeWordManager(private val context: Context) : RecognitionListener {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    var onWakeWordDetected: (() -> Unit)? = null

    fun init(onReady: () -> Unit) {
        StorageService.unpack(context, "model-en-us", "model",
            { model ->
                this.model = model
                onReady()
            },
            { exception ->
                // Tratar erro de carregamento do modelo
            }
        )
    }

    fun startListening() {
        val model = this.model ?: return
        try {
            val rec = Recognizer(model, 16000.0f)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
        } catch (e: IOException) {
            // Tratar erro
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    override fun onResult(hypothesis: String) {
        if (hypothesis.contains("khossa") || hypothesis.contains("cossa")) {
            onWakeWordDetected?.invoke()
        }
    }

    override fun onFinalResult(hypothesis: String) {
        if (hypothesis.contains("khossa") || hypothesis.contains("cossa")) {
            onWakeWordDetected?.invoke()
        }
    }

    override fun onPartialResult(hypothesis: String) {
        if (hypothesis.contains("khossa") || hypothesis.contains("cossa")) {
            onWakeWordDetected?.invoke()
        }
    }

    override fun onError(exception: Exception) {
        // Tratar erro
    }

    override fun onTimeout() {
        // Tratar timeout
    }
}
