package com.khossastudio.agent.tools
import com.khossastudio.agent.AgentAccessibilityService

import android.content.Context
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.net.wifi.WifiManager

import android.os.Build
import android.graphics.Bitmap
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.khossastudio.agent.KhossaNotificationListenerService
import com.khossastudio.agent.VisionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ferramentas nativas de controlo do Android (item 3 do briefing): abrir apps,
 * ligar, mandar mensagem, alarmes, música, ler notificações, ficheiros.
 *
 * Mesma filosofia de segurança do resto do agente (ver `NativeTools.kt`): sempre
 * que a ação normalmente teria consequência para terceiros (ligar, mandar
 * mensagem), o agente ABRE o app já preenchido e é a PESSOA quem toca em
 * ligar/enviar — nunca liga nem envia sozinho. Isto também evita permissões
 * perigosas (CALL_PHONE, SEND_SMS) que o Android/Play tratam com muita cautela.
 */
object AndroidControlTools {

    // ---------- Alarmes / lembretes rápidos ----------

    /** Abre o app de Relógio com um alarme pronto (params: hour, minute, message?). */
    fun createAlarm(context: Context, params: Map<String, String>?): String {
        val hour = params?.get("hour")?.toIntOrNull()
        val minute = params?.get("minute")?.toIntOrNull() ?: 0
        if (hour == null) return "❌ Faltou a hora do alarme (params.hour)"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, params["message"] ?: "Khossa")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "⏰ Alarme para %02d:%02d aberto no Relógio para confirmação".format(hour, minute)
        } catch (e: Exception) {
            "❌ Não há app de Relógio disponível: ${e.message}"
        }
    }

    // ---------- Chamadas (nunca automático — abre o discador) ----------

    /** Abre o discador com o número pronto; o utilizador é que toca em ligar. params: number. */
    fun openDialer(context: Context, params: Map<String, String>?): String {
        val number = params?.get("number")?.trim()
        if (number.isNullOrBlank()) return "❌ Faltou o número (params.number)"
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "📞 Discador aberto com $number — toque em ligar para confirmar"
        } catch (e: Exception) {
            "❌ Não foi possível abrir o discador: ${e.message}"
        }
    }

    // ---------- Mensagens (SMS ou WhatsApp, sempre pré-preenchidas) ----------

    /**
     * Abre o app de SMS (ou WhatsApp, se params.app = "whatsapp") com o texto
     * pronto. O utilizador é sempre quem toca em enviar — esta ação está na
     * lista de "confirmação obrigatória" do ActionExecutor mesmo em modo
     * autónomo. params: number, text, app? ("sms" | "whatsapp").
     */
    fun openMessage(context: Context, params: Map<String, String>?): String {
        val number = params?.get("number")?.trim()
        val text = params?.get("text") ?: ""
        if (number.isNullOrBlank()) return "❌ Faltou o número/contacto (params.number)"

        val app = params["app"]?.lowercase() ?: "sms"
        val intent = if (app == "whatsapp") {
            val digits = number.filter { it.isDigit() || it == '+' }
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}"))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", text)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "💬 Mensagem pronta para $number (${if (app == "whatsapp") "WhatsApp" else "SMS"}) — falta só tocar em enviar"
        } catch (e: Exception) {
            "❌ Não foi possível abrir o app de mensagens: ${e.message}"
        }
    }

    // ---------- Música (via sessão de media ativa — requer acesso a notificações) ----------

    object MediaControlTool {
        /** action: play_pause | next | previous | stop */
        fun control(context: Context, action: String): String {
            if (!KhossaNotificationListenerService.isEnabled(context)) {
                return "⚠️ Preciso de acesso a notificações para controlar música. Ative em Ajustes > Apps > Acesso a notificações > Khossa Agent."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? MediaSessionManager
                ?: return "❌ Serviço de sessões de media indisponível"
            val sessions = try {
                manager.getActiveSessions(KhossaNotificationListenerService.componentName(context.packageName))
            } catch (e: SecurityException) {
                return "⚠️ Acesso a notificações ainda não autorizado para sessões de media."
            }
            val controller = sessions.firstOrNull() ?: return "🎵 Nenhum app de música ativo no momento"
            val keyCode = when (action) {
                "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> return "❌ Ação de música desconhecida: $action"
            }
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return "🎵 Comando '$action' enviado para ${controller.packageName}"
        }
    }

    // ---------- Abrir aplicativos por nome ----------

    /** Abre um app pelo nome visível (ex.: "WhatsApp", "Câmera"). params: name. */
    fun openApp(context: Context, params: Map<String, String>?): String {
        val name = params?.get("name")?.trim()?.lowercase()
        if (name.isNullOrBlank()) return "❌ Faltou o nome do app (params.name)"

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcherIntent, 0)
        val match = candidates.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
            ?: return "❌ Não encontrei nenhum app chamado '$name' instalado"

        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (launch != null) {
                context.startActivity(launch)
                "📱 A abrir ${match.loadLabel(pm)}"
            } else "❌ Não consegui abrir ${match.loadLabel(pm)}"
        } catch (e: Exception) {
            "❌ Erro ao abrir app: ${e.message}"
        }
    }

    // ---------- Notificações recentes ----------

    /** Devolve as últimas notificações lidas (opcionalmente filtradas por app). params: app?, limit? */
    fun readNotifications(context: Context, params: Map<String, String>?): String {
        if (!KhossaNotificationListenerService.isEnabled(context)) {
            return "⚠️ Acesso a notificações não concedido. Ative em Ajustes > Apps > Acesso a notificações > Khossa Agent."
        }
        val limit = params?.get("limit")?.toIntOrNull() ?: 5
        val filter = params?.get("app")
        val items = KhossaNotificationListenerService.recent(limit, filter)
        if (items.isEmpty()) return "🔔 Nenhuma notificação recente" + (filter?.let { " de '$it'" } ?: "")
        return "🔔 " + items.joinToString(" | ") { "${it.packageName}: ${it.title ?: ""} - ${it.text ?: ""}" }
    }

    // ---------- Ficheiros (via seletor do sistema — nunca acesso direto ao armazenamento) ----------

    /**
     * Abre o seletor de ficheiros do sistema (Storage Access Framework) para o
     * utilizador escolher e partilhar/abrir um ficheiro. De propósito NÃO existe
     * um "apagar ficheiro" automático: apagar é sempre feito pela própria app de
     * ficheiros do Android, com o utilizador a confirmar — nunca o agente sozinho.
     */
    fun openFilePicker(context: Context, params: Map<String, String>?): String {
        val mime = params?.get("mime") ?: "*/*"
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            "📁 Seletor de ficheiros aberto — escolha o ficheiro desejado"
        } catch (e: Exception) {
            "❌ Não foi possível abrir o seletor de ficheiros: ${e.message}"
        }
    }

    /** Alterna a lanterna do dispositivo. params: on ("true" | "false"). */
    fun toggleFlashlight(context: Context, params: Map<String, String>?): String {
        val on = params?.get("on")?.toBoolean() ?: true
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "❌ Câmera não encontrada"
            cameraManager.setTorchMode(cameraId, on)
            "🔦 Lanterna ${if (on) "ligada" else "desligada"}"
        } catch (e: Exception) {
            "❌ Erro ao controlar lanterna: ${e.message}"
        }
    }

    /** Alterna o Wi-Fi (apenas Android < 10, para versões mais recentes abre as definições). params: on ("true" | "false"). */
    fun toggleWifi(context: Context, params: Map<String, String>?): String {
        val on = params?.get("on")?.toBoolean() ?: true
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                "📶 Wi-Fi ${if (on) "ligado" else "desligado"}"
            } catch (e: Exception) {
                "❌ Erro ao controlar Wi-Fi: ${e.message}"
            }
        } else {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "📶 Android 10+: Abrindo definições de Wi-Fi para alteração manual."
        }
    }

    /** Alterna o Bluetooth. params: on ("true" | "false"). */
    fun toggleBluetooth(context: Context, params: Map<String, String>?): String {
        val on = params?.get("on")?.toBoolean() ?: true
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "❌ Bluetooth não suportado"
        return try {
            if (on) adapter.enable() else adapter.disable()
            "🔵 Bluetooth ${if (on) "ligado" else "desligado"}"
        } catch (e: Exception) {
            "❌ Erro ao controlar Bluetooth: ${e.message}"
        }
    }

    /** Ajusta o volume do sistema. params: type ("music"|"ring"|"alarm"), direction ("up"|"down"|"mute"). */
    fun adjustVolume(context: Context, params: Map<String, String>?): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = when (params?.get("type")) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val direction = when (params?.get("direction")) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> AudioManager.ADJUST_SAME
        }
        audioManager.adjustStreamVolume(streamType, direction, AudioManager.FLAG_SHOW_UI)
        return "🔊 Volume ajustado"
    }

    /** Ajusta o brilho da tela. params: direction ("up"|"down"). */
    fun adjustBrightness(context: Context, params: Map<String, String>?): String {
        val direction = params?.get("direction") ?: "up"
        val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        val newValue = if (direction == "up") (current + 51).coerceAtMost(255) else (current - 51).coerceAtLeast(0)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newValue)
            "🔆 Brilho ajustado para ${(newValue * 100 / 255)}%"
        } catch (e: Exception) {
            "❌ Erro ao ajustar brilho (requer permissão de escrita de definições)"
        }
    }

    /** Retorna informações da bateria. */
    fun getBatteryInfo(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra("level", -1) ?: -1
        val scale = intent?.getIntExtra("scale", -1) ?: -1
        val pct = (level * 100 / scale.toFloat()).toInt()
        return "🔋 Bateria: $pct%"
    }

    /** Retorna data e hora local. */
    fun getLocalTimeInfo(): String {
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sdfDate = SimpleDateFormat("dd 'de' MMMM", Locale.getDefault())
        val now = Date()
        return "🕒 São ${sdfTime.format(now)} de ${sdfDate.format(now)}"
    }

    /** Alterna o modo de economia de bateria. params: on ("true" | "false"). */
    fun togglePowerSave(context: Context, params: Map<String, String>?): String {
        // No Android moderno, isso requer permissão de sistema ou abrir as definições
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "🔋 Abrindo definições de economia de bateria"
    }

    /** Analisa a tela atual usando MediaPipe. params: mode ("objects" | "text"). */
    fun analyzeScreen(service: AgentAccessibilityService, params: Map<String, String>?): String {
        val mode = params?.get("mode") ?: "objects"
        val screenshot: android.graphics.Bitmap? = null // Screenshot em desenvolvimento
        val visionManager = VisionManager(service)
        val result = if (mode == "text") visionManager.recognizeText(screenshot) else visionManager.detectObjects(screenshot)
        visionManager.close()
        return "🖥️ Análise de Tela: $result"
    }

    /** Analisa a câmera (última foto ou frame). params: mode ("objects" | "text"). */
    fun analyzeCamera(context: Context, params: Map<String, String>?): String {
        // Para simplificar, aqui abriríamos a câmera ou pegaríamos o último frame se o serviço de câmera estivesse ativo
        return "📷 Visão de Câmera ativada. (Requer implementação de CameraX para frames em tempo real)"
    }
}
