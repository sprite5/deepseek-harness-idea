package com.deepseek.harness.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 应用级设置（跨项目共享）。API Key 不在此存储，经 PasswordSafe 保管，
 * 应用时写入插件 DSH_HOME 的 .credentials.yaml（Step 2 实现写入）。
 *
 * 第三方 Provider API Key（MINIMAX_CN_API_KEY、AIYUNROUTER_API_KEY 等）也存于
 * 全局 .credentials.yaml 的 refs: 下，与 DEEPSEEK_API_KEY 共用同一文件，避免分散；
 * 这里仅记录"用户界面层看到的名字"（displayName），真实 key 由设置页直接读写凭据文件。
 * 用 LinkedHashMap 保持 UI 显示顺序。
 */
@State(name = "DshSettings", storages = [Storage("dsh-settings.xml")])
class DshSettingsState : PersistentStateComponent<DshSettingsState> {

    /** 模型：deepseek-chat / deepseek-reasoner */
    var model: String = "deepseek-chat"

    /** 兼容代理/自定义网关；默认官方地址 */
    var baseUrl: String = "https://api.deepseek.com"

    /** 高级：DSH_HOME 覆盖路径；null = 使用插件配置目录默认值 */
    var dshHomeOverride: String? = null

    var logLevel: String = "info"

    /**
     * 第三方 Provider 名字列表（顺序敏感：UI 按此顺序渲染）。
     * 真实 key 不存这里——存全局 .credentials.yaml 的 refs: 下，避免泄漏到 dsh-settings.xml；
     * 这里只存 provider name（如 "MINIMAX_CN_API_KEY"、"OPENAI_API_KEY"），让 UI 重启后
     * 还能记住用户配置的 provider 列表与顺序，并按此去凭据文件读真实 key 脱敏回显。
     */
    var thirdPartyProviderNames: MutableList<String> = mutableListOf()

    override fun getState(): DshSettingsState = this

    override fun loadState(state: DshSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): DshSettingsState =
            ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}
