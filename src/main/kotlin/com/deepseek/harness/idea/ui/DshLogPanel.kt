package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.i18n.DshBundle
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * DSH 日志面板（Step 5 FR-08.1）。
 *
 * 展示 dsh Node 进程 stdout/stderr（经 DshProcessManager.Listener.onLogLine 转发），
 * 只读 + 可复制 + 一键清空。上限 5000 行防内存膨胀。
 */
class DshLogPanel : JPanel(BorderLayout()) {

    private val textArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        tabSize = 2
        border = JBUI.Borders.empty(6)
    }

    init {
        val scroll = JBScrollPane(textArea)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton(DshBundle.message("log.copy")).apply { addActionListener { copyAll() } })
            add(JButton(DshBundle.message("log.clear")).apply { addActionListener { clear() } })
        }
        add(toolbar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    /** 追加一行（EDT 调用）。 */
    fun append(line: String) {
        textArea.append(line)
        if (line.isNotEmpty() && !line.endsWith("\n")) textArea.append("\n")
        // 行数上限：截断旧行
        val lines = textArea.lineCount
        if (lines > MAX_LINES) {
            val text = textArea.text
            val idx = nthIndex(text, lines - MAX_LINES)
            if (idx >= 0) textArea.text = text.substring(idx)
        }
        textArea.caretPosition = textArea.document.length
    }

    private fun copyAll() {
        val selection = textArea.selectedText ?: textArea.text
        try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(selection), null)
        } catch (e: Exception) {
            // 剪贴板失败静默（日志面板非关键路径）
        }
    }

    private fun clear() {
        textArea.text = ""
    }

    private fun nthIndex(text: String, n: Int): Int {
        var count = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                count++
                if (count >= n) return i + 1
            }
        }
        return -1
    }

    companion object {
        private const val MAX_LINES = 5000
    }
}
