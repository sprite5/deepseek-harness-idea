package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.i18n.DshBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 运行控制台右键动作："DSH 一键解释"（见 docs/DESIGN.md §3.11）。
 *
 * 注册于 `ConsoleView.PopupMenu`（Run 控制台右键组，2024.1 / 2026.2 源码均核实同 id）。
 * 与 `SendSelectionAction`（编辑器，预填等用户输入）不同：
 * 点击后**不等待用户确认**——选中日志作为用户问题（本地化解释指令 + 日志正文）
 * 自动提交给 DSH 对话（JCEF 填 composer + 派发回车）。
 */
class SendLogExplanationAction : AnAction(DshBundle.message("action.sendLogExplanation")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selected = ReadAction.compute<String, Throwable> {
            editor.selectionModel.selectedText ?: ""
        }
        if (selected.isBlank()) return
        val message = ExplainLogComposer.buildMessage(
            DshBundle.message("sendLogExplanation.prompt"),
            selected,
        )
        if (message.isBlank()) return

        val panel = DshToolWindowPanel.find(project)
        if (panel == null) {
            LOG.warn("Dsh tool window panel not available; clipboard fallback")
            ApplicationManager.getApplication().invokeLater {
                copyToClipboard(message)
                notify(project, DshBundle.message("sendLogExplanation.notRunning"))
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            panel.sendQuestion(message)
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

    companion object {
        private val LOG = Logger.getInstance(SendLogExplanationAction::class.java)
    }
}
