package com.khossastudio.agent.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lembretes leves (item "me lembra às 15h" do exemplo do briefing) — diferente
 * de `create_calendar_event` (que abre o Calendário para um evento com
 * duração/local): isto agenda uma notificação exata para uma hora, sem
 * precisar abrir nenhum app, usando AlarmManager + uma notificação local.
 *
 * Sem SCHEDULE_EXACT_ALARM concedido (Ajustes > Apps > Acesso especial >
 * Alarmes e lembretes), cai automaticamente para um alarme aproximado
 * (`setAndAllowWhileIdle`) — ainda funciona, só pode atrasar alguns minutos.
 */
object ReminderTools {
    private const val CHANNEL_ID = "khossa_reminders"
    private const val DATE_PATTERN_FULL = "yyyy-MM-dd HH:mm"
    private const val DATE_PATTERN_TIME_ONLY = "HH:mm"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lembretes do Khossa", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /**
     * Agenda um lembrete. params: message, at ("yyyy-MM-dd HH:mm" ou apenas "HH:mm"
     * para hoje/amanhã, o que fizer mais sentido a partir de agora).
     */
    fun createReminder(context: Context, params: Map<String, String>?): String {
        val message = params?.get("message")
        val at = params?.get("at")
        if (message.isNullOrBlank()) return "❌ Faltou o texto do lembrete (params.message)"
        if (at.isNullOrBlank()) return "❌ Faltou a hora do lembrete (params.at)"

        val triggerAt = parseTriggerMillis(at) ?: return "❌ Não entendi a hora '$at'"
        if (triggerAt <= System.currentTimeMillis()) return "❌ Essa hora já passou hoje — diga uma hora futura"

        ensureChannel(context)
        val id = triggerAt.toInt() // suficiente para diferenciar lembretes deste app
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }

        val horaFormatada = SimpleDateFormat("HH:mm", Locale.getDefault()).format(triggerAt)
        val precisao = if (canExact) "" else " (aproximado — ative alarmes exatos nas permissões para maior precisão)"
        return "⏰ Lembrete '$message' agendado para $horaFormatada$precisao"
    }

    /** Aceita "HH:mm" (hoje, ou amanhã se já passou) ou "yyyy-MM-dd HH:mm". */
    private fun parseTriggerMillis(at: String): Long? {
        val trimmed = at.trim()
        return try {
            if (trimmed.matches(Regex("\\d{1,2}[:h]\\d{2}"))) {
                val normalized = trimmed.replace("h", ":")
                val parts = normalized.split(":").map { it.toInt() }
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, parts[0])
                cal.set(Calendar.MINUTE, parts[1])
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1) // já passou hoje -> agenda para amanhã
                }
                cal.timeInMillis
            } else {
                SimpleDateFormat(DATE_PATTERN_FULL, Locale.getDefault()).parse(trimmed)?.time
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Dispara quando um lembrete agendado chega à hora certa: mostra uma notificação. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return
        val id = intent.getIntExtra(EXTRA_ID, 0)

        ReminderTools.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, "khossa_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Khossa — lembrete")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    companion object {
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_ID = "extra_id"
    }
}
