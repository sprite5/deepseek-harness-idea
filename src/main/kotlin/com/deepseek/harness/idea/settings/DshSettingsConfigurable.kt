package com.deepseek.harness.idea.settings

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshCredentials
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent

/**
 * 设置页（Settings → Tools → DeepSeek Harness）。
 *
 * API Key 经 [DshCredentials]（PasswordSafe）保管并在 apply 时同步到
 * [DshHomeManager] 的 DSH_HOME/.credentials.yaml；model/baseUrl 存 [DshSettingsState]。
 *
 * **脱敏回显**：账户字段回显"前 6 位 + ****** + 后 6 位"（绝不显示明文）。用 [JBTextField]（而非
 * [JBPasswordField]）以让脱敏串可被看到；isModified/apply 用"字段内容 ≠ 当前脱敏串"判定用户是否
 * 真的改了 key，从而避免把脱敏串当作真实 key 写回密码库。
 *
 * **第三方 Provider 配置**：通过 [DshThirdPartyConfigurable] 提供折叠区；存储在同一个
 * .credentials.yaml 的 refs: 下，跨项目共享。
 */
class DshSettingsConfigurable : SearchableConfigurable {

    private var apiKeyField: JBTextField? = null
    private var modelCombo: ComboBox<String>? = null
    private var baseUrlField: JBTextField? = null
    private var logLevelCombo: ComboBox<String>? = null
    private var importStatus: JBLabel? = null
    private var thirdPartyPanel: DshThirdPartyConfigurable? = null

    private var storedApiKey: String? = null

    override fun getId(): String = "dsh.settings"

    override fun getDisplayName(): String = DshBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val state = DshSettingsState.getInstance()

        storedApiKey = readStoredApiKey()
        val apiKey = JBTextField().apply {
            text = DshCredentials.maskApiKey(storedApiKey)
            columns = 40
        }
        apiKeyField = apiKey

        val model = ComboBox(arrayOf("deepseek-chat", "deepseek-reasoner")).apply {
            selectedItem = if (state.model == "deepseek-reasoner") "deepseek-reasoner" else "deepseek-chat"
        }
        modelCombo = model

        val baseUrl = JBTextField(state.baseUrl.ifEmpty { "https://api.deepseek.com" }).apply { columns = 40 }
        baseUrlField = baseUrl

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

        // 第三方 Provider 配置面板（独立 Configurable 实现，复用其 createComponent 返回的 JComponent）
        thirdPartyPanel = DshThirdPartyConfigurable()
        val thirdPartyComponent = thirdPartyPanel?.createComponent()

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(DshBundle.message("settings.apiKey.label")), apiKey, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.model.label")), model, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.baseUrl.label")), baseUrl, 1, false)
            .addComponentToRightColumn(importButton)
            .addComponentToRightColumn(status)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.logLevel.label")), logLevel, 1, false)
            .addComponent(JBLabel(DshBundle.message("settings.apply.note")))
            .addVerticalGap(8)
            .addSeparator()
            .addComponent(JBLabel(DshBundle.message("settings.thirdParty.title")))
            .addComponent(thirdPartyComponent ?: JBLabel(" "))
            .addVerticalGap(8)
            .panel
    }

    override fun isModified(): Boolean {
        val state = DshSettingsState.getInstance()
        val model = modelCombo?.selectedItem as? String
        val logLevel = logLevelCombo?.selectedItem as? String
        val mainModified = state.model != model || state.baseUrl != baseUrlField?.text?.trim().orEmpty() ||
            state.logLevel != logLevel || apiKeyChanged()
        val thirdPartyModified = thirdPartyPanel?.isModified() ?: false
        return mainModified || thirdPartyModified
    }

    private fun apiKeyChanged(): Boolean {
        val typed = apiKeyField?.text?.trim().orEmpty()
        val mask = DshCredentials.maskApiKey(storedApiKey)
        return typed != mask
    }

    override fun apply() {
        val state = DshSettingsState.getInstance()
        state.model = modelCombo?.selectedItem as? String ?: "deepseek-chat"
        state.baseUrl = baseUrlField?.text?.trim()?.ifEmpty { "https://api.deepseek.com" }
            ?: "https://api.deepseek.com"
        state.logLevel = logLevelCombo?.selectedItem as? String ?: "info"

        val key = apiKeyField?.text?.trim().orEmpty()
        if (key.isNotEmpty() && key != DshCredentials.maskApiKey(storedApiKey)) {
            DshCredentials.writeApiKey(key)
            storedApiKey = key
            ApplicationManager.getApplication().executeOnPooledThread {
                DshHomeManager.getInstance().syncCredentialsAll()
            }
        }

        // 第三方 provider 配置：apply 时调内部 panel 的 apply
        thirdPartyPanel?.apply()
    }

    override fun reset() {
        val state = DshSettingsState.getInstance()
        modelCombo?.selectedItem = state.model
        baseUrlField?.text = state.baseUrl
        logLevelCombo?.selectedItem = state.logLevel
        storedApiKey = readStoredApiKey()
        apiKeyField?.text = DshCredentials.maskApiKey(storedApiKey)
        importStatus?.text = " "
        thirdPartyPanel?.reset()
    }

    private fun readStoredApiKey(): String? {
        val globalCredFile = DshHomeManager.getInstance().globalConfigHome().resolve(".credentials.yaml")
        return DshCredentials.readApiKeyWithFallback(globalCredFile)
    }
}
