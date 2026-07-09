package com.khossastudio.agent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.khossastudio.agent.voice.KhossaVoiceService

class KhossaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_SERVICE) {
            if (KhossaVoiceService.instanceRunning) {
                KhossaVoiceService.stop(context)
            } else {
                KhossaVoiceService.start(context)
            }
            // Forçar atualização do widget
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, KhossaWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }

    companion object {
        const val ACTION_TOGGLE_SERVICE = "com.khossastudio.agent.ACTION_TOGGLE_SERVICE"

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.khossa_widget)
            
            val isRunning = KhossaVoiceService.instanceRunning
            views.setTextViewText(R.id.widget_status, if (isRunning) "Escuta ativa" else "Escuta inativa")
            views.setImageViewResource(R.id.btn_widget_toggle, if (isRunning) android.R.drawable.ic_lock_power_off else android.R.drawable.ic_media_play)

            // Intent para abrir o app
            val openAppIntent = Intent(context, AssistantActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_container, openAppPendingIntent)

            // Intent para alternar o serviço
            val toggleIntent = Intent(context, KhossaWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_SERVICE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(context, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.btn_widget_toggle, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
