package com.khossastudio.agent.smart.notifications

import android.content.Context
import android.content.pm.PackageManager
import com.khossastudio.agent.core.logging.Logger
import com.khossastudio.agent.core.registry.KhossaModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NotificationIntelligence : KhossaModule {
    override val name = "notification_intelligence"
    override val version = "1.0.0"
    override val dependencies: List<String> = listOf("core", "voice", "personality")
    
    private var isInit = false
    private var isRun = false
    private lateinit var ctx: Context
    
    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()
    
    private val _priorityNotifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val priorityNotifications: StateFlow<List<NotificationItem>> = _priorityNotifications.asStateFlow()
    
    private val _summary = MutableStateFlow(NotificationSummary())
    val summary: StateFlow<NotificationSummary> = _summary.asStateFlow()
    
    data class NotificationItem(
        val id: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val content: String,
        val timestamp: Long,
        val priority: NotificationPriority,
        val category: NotificationCategory,
        val isRead: Boolean = false
    )
    
    enum class NotificationPriority { HIGH, MEDIUM, LOW }
    enum class NotificationCategory {
        MESSAGE, CALL, SOCIAL, EMAIL, CALENDAR, ALARM, SYSTEM, OTHER
    }
    
    data class NotificationSummary(
        val totalCount: Int = 0,
        val unreadCount: Int = 0,
        val messagesCount: Int = 0,
        val priorityCount: Int = 0
    )
    
    override suspend fun initialize(context: Context): Result<Unit> = runCatching {
        ctx = context.applicationContext
        isInit = true
        Logger.d(name, "NotificationIntelligence initialized")
    }
    
    override suspend fun start(): Result<Unit> = runCatching {
        isRun = true
        Logger.d(name, "NotificationIntelligence started")
    }
    
    override suspend fun stop(): Result<Unit> = runCatching { isRun = false }
    override suspend fun reset(): Result<Unit> = runCatching {
        _notifications.value = emptyList()
    }
    override fun isInitialized() = isInit
    override fun isRunning() = isRun
    
    fun processNotification(packageName: String, title: String, content: String, timestamp: Long) {
        val category = categorize(packageName)
        val priority = determinePriority(category, content)
        val appName = getAppName(packageName)
        
        val notification = NotificationItem(
            id = "${packageName}_$timestamp",
            packageName = packageName,
            appName = appName,
            title = title,
            content = content,
            timestamp = timestamp,
            priority = priority,
            category = category
        )
        
        _notifications.value = (_notifications.value + notification).takeLast(100)
        if (priority == NotificationPriority.HIGH) {
            _priorityNotifications.value = (_priorityNotifications.value + notification).takeLast(20)
        }
        updateSummary()
        Logger.d(name, "Notification processed: $title")
    }
    
    private fun categorize(pkg: String): NotificationCategory {
        return when {
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messages") -> NotificationCategory.MESSAGE
            pkg.contains("discord") || pkg.contains("instagram") || pkg.contains("twitter") -> NotificationCategory.SOCIAL
            pkg.contains("email") || pkg.contains("mail") -> NotificationCategory.EMAIL
            pkg.contains("calendar") -> NotificationCategory.CALENDAR
            pkg.contains("phone") || pkg.contains("dialer") -> NotificationCategory.CALL
            pkg.contains("clock") || pkg.contains("alarm") -> NotificationCategory.ALARM
            else -> NotificationCategory.OTHER
        }
    }
    
    private fun determinePriority(category: NotificationCategory, content: String): NotificationPriority {
        return when (category) {
            NotificationCategory.CALL -> NotificationPriority.HIGH
            NotificationCategory.ALARM -> NotificationPriority.HIGH
            NotificationCategory.CALENDAR -> if (content.contains("agora")) NotificationPriority.HIGH else NotificationPriority.MEDIUM
            NotificationCategory.MESSAGE -> NotificationPriority.MEDIUM
            else -> NotificationPriority.LOW
        }
    }
    
    private fun getAppName(pkg: String): String = pkg.substringAfterLast(".")
    
    private fun updateSummary() {
        val notifs = _notifications.value
        _summary.value = NotificationSummary(
            totalCount = notifs.size,
            unreadCount = notifs.count { !it.isRead },
            messagesCount = notifs.count { it.category == NotificationCategory.MESSAGE },
            priorityCount = notifs.count { it.priority == NotificationPriority.HIGH }
        )
    }
    
    fun markAsRead(id: String) {
        _notifications.value = _notifications.value.map { if (it.id == id) it.copy(isRead = true) else it }
        updateSummary()
    }
    
    fun dismissNotification(id: String) {
        _notifications.value = _notifications.value.filter { it.id != id }
        _priorityNotifications.value = _priorityNotifications.value.filter { it.id != id }
        updateSummary()
    }
    
    fun clearAll() {
        _notifications.value = emptyList()
        _priorityNotifications.value = emptyList()
        updateSummary()
    }
    
    fun generateSummary(): String {
        val s = _summary.value
        return when {
            s.totalCount == 0 -> "Sem notificações nuevas."
            else -> "Você tem ${s.totalCount} notificações. ${s.messagesCount} mensagens."
        }
    }
    
    fun summarizeMessages(appName: String? = null): String {
        val messages = _notifications.value
            .filter { it.category == NotificationCategory.MESSAGE }
            .filter { appName == null || it.appName == appName }
            .takeLast(10)
        
        return if (messages.isEmpty()) "Sem mensagens nuevas."
        else buildString {
            append("Resumo das mensagens:\n")
            messages.forEach { msg ->
                append("- ${msg.appName}: \"${msg.content.take(30)}...\"\n")
            }
        }
    }
}
