package com.deepseek.harness.idea.runtime

import java.nio.charset.StandardCharsets

/**
 * 内嵌资源：dsh-mobile-hanui 插件资源（直接打入插件 jar，随插件部署）。
 * 摆脱外部 npm / runtime-bundle.zip 依赖，保证 100% 部署并生效。
 */
object MobileShellResources {

    val PACKAGE_JSON = """{
  "name": "dsh-mobile-hanui",
  "version": "0.2.5",
  "description": "Standalone mobile UI shell for the DeepSeek Harness web GUI",
  "type": "module",
  "main": "./src/index.js",
  "exports": {
    ".": "./src/index.js",
    "./client": "./src/client.js",
    "./package.json": "./package.json"
  },
  "license": "MIT",
  "dsh": {
    "bundle": {
      "patch": "./cordis.patch.yml"
    },
    "client": {
      "platform": "web",
      "inject": [
        "@deepseek-ai/dsh-client-runtime",
        "@deepseek-ai/dsh-client-ui-layout"
      ]
    }
  }
}
"""

    val CORDIS_PATCH_YML = """# dsh-mobile-hanui bundle patch
- insert:
    - id: dsh-mobile-hanui-shell
      name: dsh-mobile-hanui
"""

    val INDEX_JS = """export function apply() {}
"""

    fun clientJs(): String? =
        try {
            MobileShellResources::class.java.getResourceAsStream("/dsh-mobile-hanui/client.js")?.use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
}
