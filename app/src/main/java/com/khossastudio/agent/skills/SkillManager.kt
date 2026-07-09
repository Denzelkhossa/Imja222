package com.khossastudio.agent.skills

import android.content.Context
import java.io.File

class SkillManager(context: Context) {

    private val dir: File = File(context.filesDir, "skills").apply { mkdirs() }

    fun listSkills(): List<Skill> {
        return dir.listFiles { f -> f.isFile && f.extension == "md" }
            ?.mapNotNull { file ->
                runCatching { Skill.fromMarkdown(file.nameWithoutExtension, file.readText()) }
                    .getOrNull()
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun findRelevant(goal: String, maxResults: Int = 3): List<Skill> {
        val lowerGoal = goal.lowercase()
        return listSkills()
            .filter { skill -> skill.triggers.any { it.isNotBlank() && lowerGoal.contains(it.lowercase()) } }
            .take(maxResults)
    }

    fun save(name: String, triggers: List<String>, procedure: String): Skill {
        val id = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "skill_${System.currentTimeMillis()}" }
        val skill = Skill(id, name.trim(), triggers, procedure)
        File(dir, "$id.md").writeText(skill.toMarkdown())
        return skill
    }

    fun delete(skill: Skill) {
        File(dir, "${skill.id}.md").delete()
    }

    fun count(): Int = listSkills().size
}
