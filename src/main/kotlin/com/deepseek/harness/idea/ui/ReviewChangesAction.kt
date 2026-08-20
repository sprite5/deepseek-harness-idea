package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.review.ReviewManager
import com.deepseek.harness.idea.review.SnapshotDiff
import com.deepseek.harness.idea.review.SnapshotManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel

/**
 * "审查改动"动作 + 面板（Step 4，见 docs/DESIGN.md §3.8）。
 *
 * 打开时刷新 VFS、对比基线，列出三类差异（modified/new/deleted）；
 * 支持：查看 diff（DiffManager）、还原该文件、还原全部、忽略（接受改动）、重新基线。
 */
class ReviewChangesAction : AnAction(DshBundle.message("action.review"), null, com.intellij.icons.AllIcons.Actions.Diff) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewDialog(project).show()
    }
}

class ReviewDialog(private val project: Project) : com.intellij.openapi.ui.DialogWrapper(project, true) {

    private val snapshot = SnapshotManager(project)
    private val manager = ReviewManager(project, snapshot)
    private val model = DefaultListModel<ReviewItem>()
    private val list = JBList(model)

    init {
        title = DshBundle.message("review.title")
        init()
        refresh()
    }

    override fun createCenterPanel(): JPanel {
        list.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ReviewItem) {
                    text = value.label
                    value.change?.let { change ->
                        foreground = when (change.type) {
                            SnapshotDiff.ChangeType.MODIFIED -> java.awt.Color(200, 130, 0)
                            SnapshotDiff.ChangeType.NEW -> java.awt.Color(0, 130, 60)
                            SnapshotDiff.ChangeType.DELETED -> java.awt.Color(180, 60, 60)
                        }
                    }
                }
                return c
            }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buildActions(), BorderLayout.SOUTH)
        }
    }

    private fun buildActions(): JPanel = JPanel().apply {
        layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT)
        add(JButton(DshBundle.message("review.diff")).apply { addActionListener { showDiff() } })
        add(JButton(DshBundle.message("review.restore")).apply { addActionListener { restoreSelected() } })
        add(JButton(DshBundle.message("review.restoreAll")).apply { addActionListener { restoreAll() } })
        add(JButton(DshBundle.message("review.ignore")).apply { addActionListener { ignoreSelected() } })
        add(JButton(DshBundle.message("review.rebaseline")).apply { addActionListener { rebaseline() } })
    }

    private fun refresh() {
        snapshot.buildIfAbsent()
        val changes = manager.refreshChanges()
        model.clear()
        for (c in changes) model.addElement(ReviewItem.of(c))
        if (changes.isEmpty()) {
            model.addElement(ReviewItem.noChanges())
        }
        list.selectedIndex = if (model.size() > 0) 0 else -1
    }

    private fun showDiff() {
        selectedChange()?.let { manager.showDiff(it) }
    }

    private fun restoreSelected() {
        val c = selectedChange() ?: return
        if (manager.restoreFile(c)) {
            model.removeElementAt(list.selectedIndex)
            com.intellij.openapi.ui.Messages.showInfoMessage(project, DshBundle.message("review.restored", c.relativePath), DshBundle.message("review.title"))
        } else {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, DshBundle.message("review.restoreFailed", c.relativePath), DshBundle.message("review.title"))
        }
    }

    private fun restoreAll() {
        val changes = (0 until model.size()).mapNotNull { model.get(it)?.change }
        val ok = manager.restoreAll(changes)
        com.intellij.openapi.ui.Messages.showInfoMessage(project, DshBundle.message("review.restoredAll", ok), DshBundle.message("review.title"))
        refresh()
    }

    private fun ignoreSelected() {
        val c = selectedChange() ?: return
        manager.ignoreChange(c)
        model.removeElementAt(list.selectedIndex)
    }

    private fun rebaseline() {
        manager.rebuildBaseline()
        com.intellij.openapi.ui.Messages.showInfoMessage(project, DshBundle.message("review.rebaselined"), DshBundle.message("review.title"))
        refresh()
    }

    private fun selectedChange(): SnapshotDiff.Change? {
        val idx = list.selectedIndex
        if (idx < 0 || idx >= model.size()) return null
        return model.get(idx)?.change
    }

    private data class ReviewItem(val change: SnapshotDiff.Change?, val label: String) {
        companion object {
            fun of(change: SnapshotDiff.Change): ReviewItem {
                val prefix = when (change.type) {
                    SnapshotDiff.ChangeType.MODIFIED -> "[M] "
                    SnapshotDiff.ChangeType.NEW -> "[+] "
                    SnapshotDiff.ChangeType.DELETED -> "[-] "
                }
                return ReviewItem(change, prefix + change.relativePath)
            }
            fun noChanges(): ReviewItem = ReviewItem(null, DshBundle.message("review.noChanges"))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ReviewDialog::class.java)
    }
}
