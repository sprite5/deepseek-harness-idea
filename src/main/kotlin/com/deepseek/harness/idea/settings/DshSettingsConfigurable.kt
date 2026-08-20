package com.deepseek.harness.idea.settings

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshCredentials
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent

/**
 * 设置页（Settings → Tools → DeepSeek Harness）。
 *
 * API Key 经 [DshCredentials]（PasswordSafe）保管并在 apply 时同步到
 * [DshHomeManager] 的 DSH_HOME/.credentials.yaml；model/baseUrl 存 [DshSettingsState]。
 */
class DshSettingsConfigurable : SearchableConfigurable {

    private var apiKeyField: JBPasswordField? = null
    private var modelCombo: ComboBox<String>? = null
    private var baseUrlField: JBTextField? = null
    private var logLevelCombo: ComboBox<String>? = null
    private var importStatus: JBLabel? = null

    override fun getId(): String = "dsh.settings"

    override fun getDisplayName(): String = DshBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val state = DshSettingsState.getInstance()

        val apiKey = JBPasswordField().apply {
            text = DshCredentials.readApiKey().orEmpty()
            columns = 40
        }
        apiKeyField = apiKey

        val model = ComboBox(arrayOf("deepseek-chat", "deepseek-reasoner")).apply {
            selectedItem = if (state.model == "deepseek-reasoner") "deepseek-reasoner" else "deepseek-chat"
        }
        modelCombo = model

        val baseUrl = JBTextField(state.baseUrl.ifEmpty { "https://api.deepseek.com" }).apply { columns = 40 }
        baseUrlField = baseUrl

        // Step 5 FR-03.5：日志级别（透传 DSH_LOG_LEVEL）
        val logLevels = arrayOf("info", "debug", "warn", "error")
        val logLevel = ComboBox(logLevels).apply {
            selectedItem = logLevels.firstOrNull { it == state.logLevel } ?: "info"
        }
        logLevelCombo = logLevel

        val importButton = JButton(DshBundle.message("settings.import.button")).apply {
            addActionListener {
                importStatus?.text = "…"
                ApplicationManager.getApplication().executeOnPooledThread {
                    val key = CredentialImporter.importApiKey()
                    ApplicationManager.getApplication().invokeLater {
                        if (key == null) {
                            importStatus?.text = DshBundle.message("settings.import.failed")
                        } else {
                            apiKey.text = key
                            importStatus?.text = DshBundle.message("settings.import.done")
                        }
                    }
                }
            }
        }
        val status = JBLabel(" ")
        importStatus = status

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(DshBundle.message("settings.apiKey.label")), apiKey, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.model.label")), model, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.baseUrl.label")), baseUrl, 1, false)
            .addComponentToRightColumn(importButton)
            .addComponentToRightColumn(status)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.logLevel.label")), logLevel, 1, false)
            .addComponent(JBLabel(DshBundle.message("settings.apply.note")))
            .addVerticalGap(8)
            .panel
    }

    override fun isModified(): Boolean {
        val state = DshSettingsState.getInstance()
        val model = modelCombo?.selectedItem as? String
        val logLevel = logLevelCombo?.selectedItem as? String
        return state.model != model || state.baseUrl != baseUrlField?.text?.trim().orEmpty() ||
            state.logLevel != logLevel
    }

    override fun apply() {
        val state = DshSettingsState.getInstance()
        state.model = modelCombo?.selectedItem as? String ?: "deepseek-chat"
        state.baseUrl = baseUrlField?.text?.trim()?.ifEmpty { "https://api.deepseek.com" }
            ?: "https://api.deepseek.com"
        state.logLevel = logLevelCombo?.selectedItem as? String ?: "info"

        val key = apiKeyField?.password?.let { String(it) }
        if (!key.isNullOrEmpty()) {
            DshCredentials.writeApiKey(key)
            // 同步写入 DSH_HOME 凭据文件（运行中的会话需重启生效）
            ApplicationManager.getApplication().executeOnPooledThread {
                DshHomeManager.getInstance().syncCredentials()
            }
        }
    }

    override fun reset() {
        val state = DshSettingsState.getInstance()
        modelCombo?.selectedItem = state.model
        baseUrlField?.text = state.baseUrl
        logLevelCombo?.selectedItem = state.logLevel
        apiKeyField?.text = DshCredentials.readApiKey().orEmpty()
        importStatus?.text = " "
    }
}
