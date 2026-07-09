package com.khossastudio.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "Braços" do agente que não passam pela Accessibility API: ferramentas nativas
 * do Android (calendário, e-mail, contatos, câmera) acionadas diretamente via
 * ContentProvider/Intents do sistema. Isso resolve a limitação de a Accessibility
 * API ser "cega" pra tudo que não está visível na tela — pra essas tarefas o
 * agente nem precisa abrir/navegar pelo app correspondente.
 *
 * Ações leem e escrevem dados sensíveis do usuário (agenda, contatos); os
 * fluxos de escrita (calendário, e-mail) abrem o app correspondente já
 * preenchido para o usuário revisar e confirmar, em vez de inserir direto —
 * mesmo nível de cautela que o resto do agente aplica a ações sensíveis.
 */
object NativeTools {

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm",
        "dd/MM/yyyy HH:mm"
    )

    private fun parseMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        for (pattern in DATE_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(value)?.time
            } catch (_: Exception) {
                // tenta o próximo formato
            }
        }
        return null
    }

    /**
     * Abre o app de Calendário com o evento já preenchido (título, local, horário)
     * para o usuário confirmar a criação.
     * params esperados: title, description?, location?, start ("yyyy-MM-dd HH:mm" ou ISO), end?
     */
    fun createCalendarEvent(context: Context, params: Map<String, String>?): String {
        val title = params?.get("title")
        if (title.isNullOrBlank()) return "❌ Faltou o título do evento (params.title)"

        val startMillis = parseMillis(params["start"]) ?: System.currentTimeMillis()
        val endMillis = parseMillis(params["end"]) ?: (startMillis + 60 * 60 * 1000)

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, params["description"] ?: "")
            putExtra(CalendarContract.Events.EVENT_LOCATION, params["location"] ?: "")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "📅 Evento '$title' aberto no app de Calendário para confirmação"
        } catch (e: Exception) {
            "❌ Não há app de Calendário disponível: ${e.message}"
        }
    }

    /**
     * Abre o app de e-mail com destinatário/assunto/corpo preenchidos.
     * params esperados: to?, subject?, body?
     */
    fun sendEmail(context: Context, params: Map<String, String>?): String {
        val to = params?.get("to")
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, params?.get("subject") ?: "")
            putExtra(Intent.EXTRA_TEXT, params?.get("body") ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "✉️ App de e-mail aberto para ${to ?: "destinatário"}, pronto pra revisar e enviar"
        } catch (e: Exception) {
            "❌ Não há app de e-mail disponível: ${e.message}"
        }
    }

    /**
     * Procura contatos direto na agenda do telefone via ContentResolver — sem
     * abrir nenhum app. params esperados: query (nome a procurar)
     */
    fun searchContacts(context: Context, params: Map<String, String>?): String {
        val query = params?.get("query")?.trim()
        if (query.isNullOrBlank()) return "❌ Faltou o nome a procurar (params.query)"

        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "⚠️ Permissão de Contatos não concedida. Ative em Ajustes > Apps > Khossa Agent > Permissões > Contatos."
        }

        val results = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )
        cursor?.use {
            while (it.moveToNext() && results.size < 5) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: ""
                results.add("$name: $number")
            }
        }
        return if (results.isEmpty()) "📇 Nenhum contato encontrado para '$query'"
        else "📇 Contatos encontrados: ${results.joinToString(" | ")}"
    }

    /** Abre a câmera nativa para o usuário capturar uma foto. */
    fun openCamera(context: Context): String {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "📷 Câmera aberta"
        } catch (e: Exception) {
            "❌ Não há app de Câmera disponível: ${e.message}"
        }
    }
}
