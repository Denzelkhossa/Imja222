package com.khossastudio.agent

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converte a árvore de AccessibilityNodeInfo da tela atual numa estrutura
 * JSON simples que é enviada ao modelo de IA.
 */
object UiTreeParser {

    // Limite de elementos para não estourar o contexto do modelo
    private const val MAX_ELEMENTS = 200

    fun buildScreenJson(root: AccessibilityNodeInfo?, screenTitle: String): JSONObject {
        val result = JSONObject()
        result.put("screen", screenTitle)
        val elements = JSONArray()
        if (root != null) {
            val count = intArrayOf(0)
            collect(root, elements, count)
        }
        result.put("elements", elements)
        return result
    }

    private fun collect(node: AccessibilityNodeInfo, out: JSONArray, count: IntArray) {
        if (count[0] >= MAX_ELEMENTS) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }

        val isInteresting = label != null || node.isClickable || node.isEditable || node.isScrollable

        if (isInteresting) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)

            val obj = JSONObject()
            node.viewIdResourceName?.let { obj.put("id", it) }
            label?.let { obj.put("text", it) }
            obj.put("class", node.className?.toString() ?: "unknown")
            obj.put("clickable", node.isClickable)
            obj.put("editable", node.isEditable)
            obj.put("scrollable", node.isScrollable)
            obj.put("checked", node.isChecked)
            val boundsArr = JSONArray()
            boundsArr.put(bounds.left); boundsArr.put(bounds.top)
            boundsArr.put(bounds.right); boundsArr.put(bounds.bottom)
            obj.put("bounds", boundsArr)

            out.put(obj)
            count[0]++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collect(child, out, count)
            } finally {
                child.recycle()
            }
        }
    }
}
