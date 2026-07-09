package com.khossastudio.agent.skills

/**
 * Uma "skill" é um procedimento reutilizável e versionável que o agente aprendeu
 * (ou que o usuário ensinou manualmente). Segue o mesmo espírito do SKILL.md do
 * OpenClaw: um arquivo de texto simples com um cabeçalho (nome + gatilhos) e um
 * corpo em linguagem natural descrevendo o passo a passo.
 *
 * Exemplo de arquivo gerado (skills/pedir_uber.md):
 *
 * ---
 * name: Pedir Uber
 * trigger: uber, corrida, táxi
 * ---
 * 1. Abra o app Uber
 * 2. Toque em "Para onde?"
 * 3. Digite o endereço de destino
 * 4. Escolha a categoria UberX
 * 5. Toque em "Confirmar Uber X"
 */
data class Skill(
    val id: String,
    val name: String,
    val triggers: List<String>,
    val procedure: String
) {
    fun toMarkdown(): String = buildString {
        append("---\n")
        append("name: $name\n")
        append("trigger: ${triggers.joinToString(", ")}\n")
        append("---\n")
        append(procedure.trim())
        append("\n")
    }

    companion object {
        /** Faz o parse de um arquivo .md no formato acima. Retorna null se malformado. */
        fun fromMarkdown(id: String, raw: String): Skill? {
            if (!raw.trimStart().startsWith("---")) return null
            val parts = raw.split("---", limit = 3)
            if (parts.size < 3) return null

            var name = id
            var triggers = listOf<String>()
            parts[1].lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("name:") ->
                        name = trimmed.removePrefix("name:").trim()
                    trimmed.startsWith("trigger:") ->
                        triggers = trimmed.removePrefix("trigger:")
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                }
            }
            val body = parts[2].trim()
            if (body.isEmpty()) return null
            return Skill(id, name, triggers, body)
        }
    }
}
