package com.deepseek.harness.idea.runtime

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

/**
 * IDE 退出兜底（Step 5，plugin.xml `applicationListeners` 注册）。
 *
 * `appClosing` 时终止全部存活 DSH 面板（其 Disposable 链终止 Node 进程树），
 * 满足 PRD §7-7"关闭 IDE 后无残留 node 进程"。
 */
class DshAppLifecycleListener : AppLifecycleListener {

    override fun appClosing() {
        LOG.info("AppLifecycleListener.appClosing: disposing DSH instances")
        try {
            DshLifecycleManager.getInstance().onAppClosing()
        } catch (e: Throwable) {
            LOG.warn("app closing cleanup failed", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DshAppLifecycleListener::class.java)
    }
}
