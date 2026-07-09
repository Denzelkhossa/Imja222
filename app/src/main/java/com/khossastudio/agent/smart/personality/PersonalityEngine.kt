package com.khossastudio.agent.smart.personality

import android.content.Context
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

class PersonalityEngine : KhossaModule {
    override val name = "personality"
    override val version = "1.0.0"
    override val dependencies: List<String> = listOf("core")
    
    private var isInit = false
    private var isRun = false
    
    private val _mood = MutableStateFlow(PersonalityMood.PROFESSIONAL)
    val mood: StateFlow<PersonalityMood> = _mood.asStateFlow()
    
    private val _curiosity = MutableStateFlow(CuriosityLevel.NORMAL)
    val curiosity: StateFlow<CuriosityLevel> = _curiosity.asStateFlow()
    
    private val _personalityProfile = MutableStateFlow(PersonalityProfile())
    val personalityProfile: StateFlow<PersonalityProfile> = _personalityProfile.asStateFlow()
    
    enum class PersonalityMood { 
        PROFESSIONAL,   // Formal, direto
        FRIENDLY,       // Amigável, acolhedor
        ENERGETIC,      // Animado, entusiasmado
        CALM,           // Calmo, sereno
        CURIOUS         // Curioso, questionador
    }
    
    enum class CuriosityLevel { LOW, NORMAL, HIGH }
    
    data class PersonalityProfile(
        val name: String = "Khossa",
        val voiceStyle: VoiceStyle = VoiceStyle.MODERN_ASSISTANT,
        val formalityLevel: Int = 50, // 0-100
        val humorLevel: Int = 30,     // 0-100
        val verbosityLevel: Int = 50,  // 0-100
        val curiosityLevel: Int = 50   // 0-100
    )
    
    enum class VoiceStyle {
        MODERN_ASSISTANT,  // Futurista, tecnológico
        PROFESSIONAL,      // Corporativo, eficiente
        FRIENDLY,          // Quente, acessível
        SMART              // Inteligente, analítico
    }
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        isInit = true
        Logger.d(name, "PersonalityEngine initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "PersonalityEngine started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching { isRun = false }
    override suspend fun reset(): Result<Unit> = runCatching { 
        _mood.value = PersonalityMood.PROFESSIONAL 
        _curiosity.value = CuriosityLevel.NORMAL
    }
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun setMood(mood: PersonalityMood) {
        _mood.value = mood
        Logger.d(name, "Mood changed to: $mood")
    }
    
    fun setCuriosity(level: CuriosityLevel) {
        _curiosity.value = level
    }
    
    fun updateProfile(updates: (PersonalityProfile) -> PersonalityProfile) {
        _personalityProfile.value = updates(_personalityProfile.value)
    }
    
    fun adaptToContext(context: ConversationContext) {
        _mood.value = when (context) {
            ConversationContext.MORNING -> PersonalityMood.FRIENDLY
            ConversationContext.WORK -> PersonalityMood.PROFESSIONAL
            ConversationContext.RELAXED -> PersonalityMood.CALM
            ConversationContext.EXCITED -> PersonalityMood.ENERGETIC
            ConversationContext.QUESTIONING -> PersonalityMood.CURIOUS
            else -> _mood.value
        }
    }
    
    enum class ConversationContext { 
        MORNING, WORK, RELAXED, EXCITED, QUESTIONING, GENERAL 
    }
    
    // Generate contextual response
    fun generateResponse(action: AssistantAction): String {
        return when (action) {
            is AssistantAction.ACTION_EXECUTED -> actionResponse(action)
            is AssistantAction.CONFIRMATION_NEEDED -> confirmationResponse(action)
            is AssistantAction.QUESTION_ASKED -> questionResponse(action)
            is AssistantAction.ERROR_OCCURRED -> errorResponse(action)
            is AssistantAction.GREETING -> greetingResponse()
            is AssistantAction.FAREWELL -> farewellResponse()
            is AssistantAction.CURIOSITY_TRIGGERED -> curiosityResponse(action)
            is AssistantAction.PROACTIVE_SUGGESTION -> suggestionResponse(action)
        }
    }
    
    private fun actionResponse(action: AssistantAction.ACTION_EXECUTED): String {
        val profile = _personalityProfile.value
        val verbosity = profile.verbosityLevel
        
        return when {
            verbosity > 70 -> "Feito! ${action.description}. ${action.details ?: ""}"
            verbosity > 40 -> "Pronto! ${action.description}."
            else -> "Feito."
        }
    }
    
    private fun confirmationResponse(action: AssistantAction.CONFIRMATION_NEEDED): String {
        val profile = _personalityProfile.value
        val curiosity = _curiosity.value
        
        val baseResponse = if (profile.formalityLevel > 60) {
            "Posso executar: ${action.action}. Confirma?"
        } else {
            "Posso fazer isso: ${action.action}. Tudo bem?"
        }
        
        // Add curious question if high curiosity
        if (curiosity == CuriosityLevel.HIGH && Random.nextFloat() > 0.7f) {
            val question = generateCuriousQuestion(action)
            return "$baseResponse $question"
        }
        
        return baseResponse
    }
    
    private fun generateCuriousQuestion(action: AssistantAction.CONFIRMATION_NEEDED): String {
        val questions = listOf(
            "Quer que eu ajuste algo antes?",
            "Posso adicionar mais alguma coisa?",
            "Quer que eu configure algo similar para outras situações?",
            "Posso perguntar uma coisa antes de executar?"
        )
        return questions.random()
    }
    
    private fun questionResponse(action: AssistantAction.QUESTION_ASKED): String {
        val profile = _personalityProfile.value
        val mood = _mood.value
        
        val question = action.question
        
        // Generate contextual question based on the topic
        return when {
            question.contains("quando", ignoreCase = true) -> {
                "Hmm, interessante pergunta. Você quer que eu monitore isso e te avise quando acontecer?"
            }
            question.contains("por que", ignoreCase = true) -> {
                "Boa pergunta! Enquanto executo, posso pesquisar isso para você. Quer que eu te explique?"
            }
            question.contains("como", ignoreCase = true) -> {
                "Conheço algumas formas de fazer isso. Quer que eu sugira a melhor opção?"
            }
            mood == PersonalityMood.CURIOUS -> {
                "Ótima pergunta! Posso tentar responder, mas se não souber, vou pesquisar. Combinado?"
            }
            else -> "Boa pergunta! Vou verificar isso para você."
        }
    }
    
    private fun errorResponse(action: AssistantAction.ERROR_OCCURRED): String {
        val profile = _personalityProfile.value
        val verbosity = profile.verbosityLevel
        
        return when {
            verbosity > 60 -> "Ops! ${action.errorMessage}. Tentando de outra forma..."
            verbosity > 30 -> "Hmm, algo deu errado. Já estou tentando resolver."
            else -> "Erro. Tentando novamente."
        }
    }
    
    private fun greetingResponse(): String {
        val profile = _personalityProfile.value
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        
        val timeGreeting = when {
            hour < 6 -> "Boa madrugada"
            hour < 12 -> "Bom dia"
            hour < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
        
        return if (profile.formalityLevel > 60) {
            "$timeGreeting! Como posso ajudar?"
        } else {
            "$timeGreeting! Em que posso te ajudar hoje?"
        }
    }
    
    private fun farewellResponse(): String {
        val profile = _personalityProfile.value
        
        return if (profile.formalityLevel > 60) {
            "Até logo! Estarei aqui quando precisar."
        } else {
            "Tchau! Se precisar, é só chamar!"
        }
    }
    
    private fun curiosityResponse(action: AssistantAction.CURIOSITY_TRIGGERED): String {
        return when (action.topic) {
            "routine" -> "Já percebi que você costuma fazer isso nessa hora. Quer que eu automatize?"
            "preference" -> "Interessante... Você prefere assim. Posso lembrar para próximas vezes?"
            "habit" -> "Isso é algo que você faz sempre. Quer que eu crie uma rotina?"
            "weather" -> "O clima está mudando. Quer que eu ajuste algo com base nisso?"
            "time" -> "Já é essa hora? O dia passou rápido! Quer que eu organize o resto?"
            else -> "Hmm, isso é novo. Posso aprender com isso para te ajudar melhor."
        }
    }
    
    private fun suggestionResponse(action: AssistantAction.PROACTIVE_SUGGESTION): String {
        return when (action.suggestion) {
            "battery_low" -> "Bateria está baixa. Quer que eu ative o modo economia?"
            "meeting_soon" -> "Você tem uma reunião em 15 minutos. Quer que eu prepare?"
            "route_suggestion" -> "Com o trânsito agora, talvez seja melhor sair mais cedo. Quer que eu abra o Maps?"
            "routine_detected" -> "Parece que você faz isso todos os dias. Quer criar uma automação?"
            "app_suggestion" -> "Baseado no horário, você costuma usar este app agora. Quer que eu abra?"
            else -> "Tenho uma sugestão: ${action.suggestion}"
        }
    }
    
    // SEED responses - curious responses during actions
    fun generateCuriousObservation(context: ActionContext): String? {
        if (_curiosity.value == CuriosityLevel.LOW) return null
        if (Random.nextFloat() > 0.3f) return null // Only 30% chance
        
        return when (context) {
            ActionContext.OPENING_APP -> {
                val observations = listOf(
                    "Você usa muito esse app, hein!",
                    "Curiosidade: esse app é um dos seus favoritos.",
                    "Posso perguntar? Por que esse app especificamente?",
                    "Já vi você usar bastante esse app. Quer adicionar aos favoritos?"
                )
                observations.random()
            }
            ActionContext.SENDING_MESSAGE -> {
                val observations = listOf(
                    "Vou enviar agora. Quer que eu te mostre o que escrevi?",
                    "Já estou enviando. Quer adicionar algo mais?",
                    "Mensagem pronta! Quer revisar antes de enviar?"
                )
                observations.random()
            }
            ActionContext.SETTING_ALARM -> {
                val observations = listOf(
                    "Alarme criado! Quer que eu configure uma rotina para esse horário?",
                    "Já preparei o alarme. Quer que eu te acorde com uma mensagem especial?",
                    "Pronto! Pergunta: você quer que eu te dê boas-vindas ao acordar?"
                )
                observations.random()
            }
            ActionContext.CHANGING_SETTINGS -> {
                val observations = listOf(
                    "Configuração alterada! Quer que eu salve isso como padrão?",
                    "Feito! Posso lembrar dessa preferência para você.",
                    "Pronto. Quer que eu configure o mesmo para outras situações?"
                )
                observations.random()
            }
            else -> null
        }
    }
    
    enum class ActionContext { 
        OPENING_APP, SENDING_MESSAGE, SETTING_ALARM, 
        CHANGING_SETTINGS, SEARCHING, PLAYING_MUSIC, NAVIGATING 
    }
    
    sealed class AssistantAction {
        data class ACTION_EXECUTED(val description: String, val details: String? = null) : AssistantAction()
        data class CONFIRMATION_NEEDED(val action: String) : AssistantAction()
        data class QUESTION_ASKED(val question: String) : AssistantAction()
        data class ERROR_OCCURRED(val errorMessage: String) : AssistantAction()
        object GREETING : AssistantAction()
        object FAREWELL : AssistantAction()
        data class CURIOSITY_TRIGGERED(val topic: String) : AssistantAction()
        data class PROACTIVE_SUGGESTION(val suggestion: String) : AssistantAction()
    }
}
