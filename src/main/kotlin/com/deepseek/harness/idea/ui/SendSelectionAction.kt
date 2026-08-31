package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.i18n.DshBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.lang.LanguageUtil

/**
 * 编辑器右键动作："发送选中代码到 DSH"（Step 4，见 docs/DESIGN.md §3.7）。
 *
 * 1. ReadAction 读选中文本/文件/语言（>64KB 截断并注明）；
 * 2. 通知 DshToolWindowPanel：写入 Bridge sent-selection 队列 + 聚焦工具窗口 +
 *    JCEF 注入预填（失败降级剪贴板 + 通知）。
 */
class SendSelectionAction : AnAction(DshBundle.message("action.sendSelection")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null &&
            (editor.selectionModel.hasSelection() || editor.document.text.isNotBlank())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val data = ReadAction.compute<SelectionData, Throwable> {
            val doc = editor.document
            val sel = editor.selectionModel
            val vf = FileDocumentManager.getInstance().getFile(doc)
            val raw = if (sel.hasSelection()) sel.selectedText ?: "" else doc.text
            val truncated = raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES
            val capped = if (truncated) raw.take(MAX_BYTES / 4) + "\n…(已截断，超出 64KB)" else raw
            val language = vf?.let { LanguageUtil.getLanguageForPsi(project, it)?.id?.takeIf { l -> l.isNotBlank() } }
                ?: vf?.extension
            SelectionData(
                filePath = vf?.path,
                language = language,
                selection = capped,
                lineStart = if (sel.hasSelection()) doc.getLineNumber(sel.selectionStart) + 1 else 0,
                lineEnd = if (sel.hasSelection()) doc.getLineNumber(sel.selectionEnd) + 1 else 0,
            )
        }

        val panel = DshToolWindowPanel.find(project)
        if (panel == null) {
            LOG.warn("Dsh tool window panel not available; clipboard fallback")
            ApplicationManager.getApplication().invokeLater {
                copyToClipboard(data.selection)
                notify(project, DshBundle.message("sendSelection.clipboard"))
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            panel.sendSelection(data.filePath, data.language, data.selection, data.lineStart, data.lineEnd)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        } catch (ex: Exception) {
            LOG.warn("clipboard failed", ex)
        }
    }

    private fun notify(project: Project, content: String) {
        com.intellij.notification.Notifications.Bus.notify(
            com.intellij.notification.Notification(
                "DSH Simple",
                "",
                content,
                com.intellij.notification.NotificationType.INFORMATION,
            ),
            project,
        )
    }

    private data class SelectionData(
        val filePath: String?,
        val language: String?,
        val selection: String,
        val lineStart: Int,
        val lineEnd: Int,
    )

    companion object {
        private val LOG = Logger.getInstance(SendSelectionAction::class.java)
        private const val MAX_BYTES = 64 * 1024
    }
}
