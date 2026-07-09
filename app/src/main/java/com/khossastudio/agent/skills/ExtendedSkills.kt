package com.khossastudio.agent.skills

import android.content.Context
import com.khossastudio.agent.core.events.EventBus
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Extended Skill Manager - Plugin architecture with categories, permissions, and priority.
 */
class ExtendedSkills : KhossaModule {
    override val name = "extended_skills"
    override val version = "1.0.0"
    override val dependencies = listOf("core")
    
    private lateinit var context: Context
    private var isInit = false
    private var isRun = false
    
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
    
    private val _activeSkills = MutableStateFlow<Set<String>>(emptySet())
    val activeSkills: StateFlow<Set<String>> = _activeSkills.asStateFlow()
    
    data class Skill(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val author: String,
        val commands: List<String>,
        val permissions: List<String>,
        val categories: List<String>,
        val priority: Int,
        val dependencies: List<String>,
        val isBuiltIn: Boolean = false
    )
    
    // Built-in skills
    private val builtInSkills = listOf(
        Skill("music", "Music Skill", "Control music playback", "1.0.0", "Khossa",
            listOf("tocar", "pausar", "próxima", "anterior", "stop", "play", "pause"),
            emptyList(), listOf("media", "music"), 10, emptyList(), true),
        Skill("whatsapp", "WhatsApp Skill", "Send messages via WhatsApp", "1.0.0", "Khossa",
            listOf("enviar mensagem", "mandar zap", "whatsapp"),
            emptyList(), listOf("communication", "messaging"), 20, emptyList(), true),
        Skill("navigation", "Navigation Skill", "Control device navigation", "1.0.0", "Khossa",
            listOf("voltar", "home", "menu", "back"),
            emptyList(), listOf("system", "navigation"), 100, emptyList(), true),
        Skill("bluetooth", "Bluetooth Skill", "Control Bluetooth", "1.0.0", "Khossa",
            listOf("ligar bluetooth", "desligar bluetooth", "bluetooth"),
            listOf("android.permission.BLUETOOTH"), listOf("system", "connectivity"), 30, emptyList(), true),
        Skill("wifi", "WiFi Skill", "Control WiFi", "1.0.0", "Khossa",
            listOf("ligar wifi", "desligar wifi", "wifi"),
            emptyList(), listOf("system", "connectivity"), 30, emptyList(), true),
        Skill("flashlight", "Flashlight Skill", "Control flashlight", "1.0.0", "Khossa",
            listOf("lanterna", "flashlight", "luz"),
            emptyList(), listOf("system"), 30, emptyList(), true),
        Skill("camera", "Camera Skill", "Take photos and videos", "1.0.0", "Khossa",
            listOf("tirar foto", "abrir câmara", "câmera", "foto"),
            listOf("android.permission.CAMERA"), listOf("media", "camera"), 20, emptyList(), true),
        Skill("calendar", "Calendar Skill", "Manage calendar events", "1.0.0", "Khossa",
            listOf("evento", "calendário", "agendar", "meeting"),
            listOf("android.permission.READ_CALENDAR"), listOf("productivity", "calendar"), 20, emptyList(), true),
        Skill("alarm", "Alarm Skill", "Set alarms and reminders", "1.0.0", "Khossa",
            listOf("alarme", "lembrete", "lembra", "alarm", "reminder"),
            listOf("android.permission.SCHEDULE_EXACT_ALARM"), listOf("productivity", "reminders"), 25, emptyList(), true),
        Skill("settings", "Settings Skill", "Open and control device settings", "1.0.0", "Khossa",
            listOf("configurações", "definições", "settings", "abrir configurações"),
            emptyList(), listOf("system"), 15, emptyList(), true),
        Skill("phone", "Phone Skill", "Make phone calls", "1.0.0", "Khossa",
            listOf("ligar para", "telefonar", "call", "phone"),
            emptyList(), listOf("communication", "phone"), 20, emptyList(), true),
        Skill("automation", "Automation Skill", "Create and manage automations", "1.0.0", "Khossa",
            listOf("automatizar", "rotina", "automação", "automation", "routine"),
            emptyList(), listOf("productivity", "automation"), 5, emptyList(), true),
        Skill("vision", "Vision Skill", "Analyze screen and camera", "1.0.0", "Khossa",
            listOf("o que é", "descreve", "ocr", "lê", "deteta", "analyze"),
            listOf("android.permission.CAMERA"), listOf("ai", "vision"), 10, emptyList(), true)
    )
    
    override suspend fun initialize(ctx: Context): Result<Unit> = runCatching {
        context = ctx.applicationContext
        _skills.value = builtInSkills
        _activeSkills.value = builtInSkills.map { it.id }.toSet()
        isInit = true
        Logger.d(name, "ExtendedSkills initialized with ${builtInSkills.size} skills")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "ExtendedSkills started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching {
        isRun = false
        Logger.d(name, "ExtendedSkills stopped")
    }
    
    override suspend fun reset(): Result<Unit> = runCatching {
        _activeSkills.value = builtInSkills.map { it.id }.toSet()
    }
    
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun registerSkill(skill: Skill) {
        _skills.value = _skills.value.filter { it.id != skill.id } + skill
        _activeSkills.value = _activeSkills.value + skill.id
        EventBus.publish(EventBus.ModuleEvent(name, "skill_registered", mapOf("skillId" to skill.id)))
    }
    
    fun enableSkill(skillId: String) {
        if (_skills.value.any { it.id == skillId }) {
            _activeSkills.value = _activeSkills.value + skillId
        }
    }
    
    fun disableSkill(skillId: String) {
        _activeSkills.value = _activeSkills.value - skillId
    }
    
    fun findSkillsForCommand(command: String): List<Skill> {
        val normalized = command.lowercase()
        return _skills.value
            .filter { it.id in _activeSkills.value }
            .filter { skill -> skill.commands.any { normalized.contains(it) } }
            .sortedBy { it.priority }
    }
    
    fun getCategories(): List<String> = _skills.value.flatMap { it.categories }.distinct().sorted()
    
    fun getSkill(id: String): Skill? = _skills.value.find { it.id == id }
}
