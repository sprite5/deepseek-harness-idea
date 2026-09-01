package com.deepseek.harness.idea.settings

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshCredentials
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.DefaultComboBoxModel

/**
 * 第三方 Provider API Key 配置面板（Settings → Tools → DeepSeek Harness 顶部子区）。
 *
 * 存储：与 DEEPSEEK_API_KEY 共用全局 .credentials.yaml 的 refs: 节。
 * Apply 时调 DshHomeManager.syncCredentialsAll 推送到各项目 DSH_HOME。
 */
class DshThirdPartyConfigurable : Configurable {

    private val state: DshSettingsState
        get() = DshSettingsState.getInstance()

    private var listPanel: JPanel? = null
    private var addCombo: ComboBox<String>? = null

    private val rows = LinkedHashMap<String, JBTextField>()
    private val nameFields = LinkedHashMap<String, JBTextField>()
    private var storedKeys: Map<String, String> = emptyMap()

    override fun getDisplayName(): String = DshBundle.message("settings.thirdParty.displayName")

    override fun createComponent(): JComponent {
        loadStoredKeys()

        val main = JPanel(BorderLayout())

        val header = JBLabel(DshBundle.message("settings.thirdParty.hint"))
        header.alignmentX = 0f

        listPanel = JPanel(VerticalLayout(4))
        rebuildRows()

        val addChoices = (PRESETS + CUSTOM_LABEL).toTypedArray()
        val addModel = DefaultComboBoxModel(addChoices)
        addCombo = ComboBox(addModel)
        val decorated = ToolbarDecorator.createDecorator(listPanel)
            .addAction { addProviderAction() }
            .setRemoveAction { removeSelectedAction() }
            .disableUpDownActions()
            .createPanel()

        main.add(header, BorderLayout.NORTH)
        main.add(decorated, BorderLayout.CENTER)
        return FormBuilder.createFormBuilder()
            .addComponent(main)
            .addComponent(JBLabel(DshBundle.message("settings.thirdParty.apply.note")))
            .panel
    }

    private fun rebuildRows() {
        rows.clear()
        nameFields.clear()
        listPanel?.removeAll()
        val names = if (state.thirdPartyProviderNames.isEmpty()) {
            mutableListOf(PRESETS.first())
        } else {
            state.thirdPartyProviderNames.toMutableList()
        }
        for (name in names) {
            addRow(name, storedKeys[name])
        }
        listPanel?.revalidate()
        listPanel?.repaint()
    }

    private fun addRow(name: String, storedKey: String?) {
        val panel = JPanel(BorderLayout(8, 0))
        val nameField = JBTextField(name).apply { columns = 24 }
        val keyField = JBTextField(DshCredentials.maskApiKey(storedKey)).apply {
            columns = 36
            toolTipText = DshBundle.message("settings.thirdParty.key.tooltip")
        }
        val label = JBLabel(DshBundle.message("settings.thirdParty.provider.label"))
        panel.add(label, BorderLayout.WEST)
        val center = JPanel(BorderLayout(4, 0))
        center.add(nameField, BorderLayout.CENTER)
        center.add(JBLabel("→"), BorderLayout.EAST)
        val right = JPanel(BorderLayout(4, 0))
        right.add(center, BorderLayout.CENTER)
        right.add(keyField, BorderLayout.EAST)
        panel.add(right, BorderLayout.CENTER)
        rows[name] = keyField
        nameFields[name] = nameField
        listPanel?.add(panel)
    }

    private fun addProviderAction() {
        val choice = addCombo?.selectedItem as? String ?: PRESETS.first()
        val newName = if (choice == CUSTOM_LABEL) "__NEW__" + rows.size else choice
        if (rows.containsKey(newName)) return
        addRow(newName, storedKeys[newName])
        listPanel?.revalidate()
        listPanel?.repaint()
    }

    private fun removeSelectedAction() {
        val comp = listPanel ?: return
        if (comp.componentCount == 0) return
        val last = comp.componentCount - 1
        comp.remove(last)
        val lastKey = rows.keys.lastOrNull()
        if (lastKey != null) {
            rows.remove(lastKey)
            nameFields.remove(lastKey)
        }
        comp.revalidate()
        comp.repaint()
    }

    private fun loadStoredKeys() {
        val credFile = DshHomeManager.getInstance().globalConfigHome().resolve(".credentials.yaml")
        val all = DshCredentials.readAllRefs(credFile)
        storedKeys = all.filterKeys { it != DshCredentials.DEEPSEEK_API_KEY }
    }

    override fun isModified(): Boolean {
        val currentNames = rows.keys.toList()
        val savedNames = state.thirdPartyProviderNames
        val namesChanged = currentNames != savedNames
        val keysChanged = rows.any { (name, field) ->
            val typed = field.text.trim()
            val mask = DshCredentials.maskApiKey(storedKeys[name])
            typed.isNotEmpty() && typed != mask
        }
        return namesChanged || keysChanged
    }

    override fun apply() {
        val newNames = mutableListOf<String>()
        val newRefs = LinkedHashMap<String, String>()
        val credFile = DshHomeManager.getInstance().globalConfigHome().resolve(".credentials.yaml")
        val allRefs = LinkedHashMap(DshCredentials.readAllRefs(credFile))
        val deepseekKey = allRefs.remove(DshCredentials.DEEPSEEK_API_KEY)

        for ((oldName, field) in rows) {
            val nameField = nameFields[oldName]
            val actualName = nameField?.text?.trim()?.uppercase() ?: oldName
            if (actualName.isEmpty() || actualName == CUSTOM_LABEL) continue
            newNames.add(actualName)
            val typed = field.text.trim()
            if (typed.isEmpty()) continue
            val mask = DshCredentials.maskApiKey(storedKeys[oldName])
            val keyValue = if (typed == mask) storedKeys[oldName] ?: continue else typed
            newRefs[actualName] = keyValue
        }

        val finalRefs = LinkedHashMap<String, String>()
        if (deepseekKey != null) finalRefs[DshCredentials.DEEPSEEK_API_KEY] = deepseekKey
        finalRefs.putAll(newRefs)
        DshCredentials.writeRefs(credFile, finalRefs)

        ApplicationManager.getApplication().executeOnPooledThread {
            DshHomeManager.getInstance().syncCredentialsAll()
        }

        state.thirdPartyProviderNames = newNames

        loadStoredKeys()
        rebuildRows()
    }

    override fun reset() {
        loadStoredKeys()
        rebuildRows()
    }

    companion object {
        private val PRESETS = listOf(
            "MINIMAX_CN_API_KEY",
            "AIYUNROUTER_API_KEY",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "GOOGLE_API_KEY",
        )
        private const val CUSTOM_LABEL = "(custom)"
    }
}
