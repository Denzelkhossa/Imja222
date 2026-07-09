package com.khossastudio.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Serviço de Acessibilidade que dá ao agente a capacidade de "ver" a tela
 * e executar ações (clique, digitação, rolagem, gestos) em qualquer app.
 *
 * Só existe uma instância ativa por vez em Android; guardamos uma referência
 * estática para que a MainActivity/TaskPlanner consigam falar com ela.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisamos reagir a cada evento; a captura de tela é feita sob demanda
        // pelo TaskPlanner via getCurrentScreenJson().
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun getCurrentScreenJson(): JSONObject {
        val root = rootInActiveWindow
        val title = rootInActiveWindow?.packageName?.toString() ?: "desconhecido"
        return UiTreeParser.buildScreenJson(root, title)
    }

    // ---------- Localização de elementos ----------

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = root.findAccessibilityNodeInfosByText(text)
        return matches?.firstOrNull()
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = root.findAccessibilityNodeInfosByViewId(id)
        return matches?.firstOrNull()
    }

    // ---------- Execução de ações ----------

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        // Sobe na árvore até achar um ancestral clicável, se o nó exato não for
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        val finalTarget = target ?: node
        return finalTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun clickAtBounds(bounds: Rect): Boolean {
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val path = Path()
        path.moveTo(cx, cy)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
