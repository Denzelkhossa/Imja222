package com.khossastudio.agent

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Dá ao agente acesso a "ler notificações" (item 3 do briefing) e é também o
 * que habilita o controlo de música (item 3: "controlar música") — no Android
 * moderno, controlar a sessão de media de outro app (play/pause/próxima) exige
 * que o teu app seja um Notification Listener autorizado; ver
 * `tools/AndroidControlTools.kt` → `MediaControlTool`, que usa
 * `MediaSessionManager.getActiveSessions(NOTIFICATION_LISTENER_COMPONENT)`.
 *
 * Ativar em: Ajustes > Apps > Acesso especial > Acesso a notificações > Khossa Agent.
 * (o botão `btnNotificationAccess` no ecrã de Configurações avançadas abre isso.)
 *
 * Por segurança/privacidade, só guardamos as últimas [MAX_BUFFER] notificações
 * em memória (RAM, não em disco) — o agente só as usa quando o utilizador pede
 * algo como "o que me mandaram no WhatsApp agora?"; nada é enviado para
 * nenhum servidor a não ser que entre no texto de um pedido à IA.
 */
class KhossaNotificationListenerService : NotificationListenerService() {

    data class SimpleNotification(
        val packageName: String,
        val title: String?,
        val text: String?,
        val postedAt: Long
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        synchronized(buffer) {
            buffer.add(0, SimpleNotification(notification.packageName, title, text, System.currentTimeMillis()))
            while (buffer.size > MAX_BUFFER) buffer.removeAt(buffer.size - 1)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // não precisamos de reagir à remoção
    }

    companion object {
        @Volatile var instance: KhossaNotificationListenerService? = null
        private const val MAX_BUFFER = 30
        private val buffer = mutableListOf<SimpleNotification>()

        /** Nome do componente usado pelo MediaSessionManager para pedir acesso às sessões de media ativas. */
        fun componentName(pkg: String) = ComponentName(pkg, KhossaNotificationListenerService::class.java.name)

        fun isEnabled(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat?.contains(context.packageName) == true
        }

        /** Últimas notificações lidas, mais recentes primeiro. Filtra por app se [packageFilter] for dado. */
        fun recent(limit: Int = 10, packageFilter: String? = null): List<SimpleNotification> {
            synchronized(buffer) {
                val src = if (packageFilter != null) buffer.filter { it.packageName.contains(packageFilter, ignoreCase = true) } else buffer
                return src.take(limit)
            }
        }
    }
}
