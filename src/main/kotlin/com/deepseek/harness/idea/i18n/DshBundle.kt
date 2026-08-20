package com.deepseek.harness.idea.i18n

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

/**
 * 中英双语资源包入口（messages/DshBundle.properties + _zh_CN.properties）。
 * 通过 IntelliJ 的 DynamicBundle 自动跟随 IDE 语言切换。
 */
object DshBundle : DynamicBundle("messages.DshBundle") {
    @Nls
    fun message(@PropertyKey(resourceBundle = "messages.DshBundle") key: String, vararg params: Any): String =
        getMessage(key, *params)
}
