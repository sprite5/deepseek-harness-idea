package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 运行时下载器入口（瘦身通用插件的运行时下发）。可注入 [RuntimeFetcher] 便于单测。
 *
 * 流程：本地已有运行时短路 → 组装下载 URL（资产地图 + 插件版本）→ 拉取校验和侧车 `.sha256`
 * → 下载 bundle → SHA-256 比对（不一致拒绝）→ 安全解压到运行时根 → 再次校验 node + dsh 就绪。
 *
 * 信任锚定到同源 `.sha256` 侧车；任一步失败返回 false（调用方给出可操作报错）。
 */
object RuntimeProvisioner {

    private val LOG = Logger.getInstance(RuntimeProvisioner::class.java)

    /** 下载抽象（接口，便于测试注入）。 */
    interface RuntimeFetcher {
        /** 把 [url] 下载到 [dest]；成功返回 true。 */
        fun download(url: String, dest: Path): Boolean

        /** 拉取 [url] 全文（校验和侧车）；失败返回 null。 */
        fun fetchText(url: String): String?
    }

    /** 默认实现：HttpURLConnection（仅 loopback / https；不依赖第三方栈）。 */
    object HttpFetcher : RuntimeFetcher {
        override fun download(url: String, dest: Path): Boolean = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.inputStream.use { Files.copy(it, dest, StandardCopyOption.REPLACE_EXISTING) }
            conn.disconnect()
            true
        } catch (e: Exception) {
            LOG.warn("runtime download failed: $url", e)
            false
        }

        override fun fetchText(url: String): String? = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val text = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).trim()
            conn.disconnect()
            text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 确保运行时就绪（[dest] = 运行时根）。
     * @return true=可用；false=失败（含当前平台无资产 / 下载失败 / 校验不过 / 解压后不完整）。
     */
    fun provision(
        dest: Path,
        spec: RuntimeAssetSpec,
        version: String,
        fetcher: RuntimeFetcher = HttpFetcher,
    ): Boolean {
        if (isPresent(dest)) return true
        if (spec.baseUrl.isBlank()) {
            LOG.warn("runtime download base url is empty; cannot provision")
            return false
        }
        val target = Platform.current()
        val assetUrl = spec.urlFor(target, version) ?: run {
            LOG.warn("no runtime asset for platform ${target.id} (assets=${spec.assets.keys})")
            return false
        }
        val shaUrl = spec.shaUrlFor(target, version) ?: return false
        val expected = fetcher.fetchText(shaUrl)?.takeIf { it.isNotBlank() } ?: run {
            LOG.warn("failed to fetch runtime checksum $shaUrl")
            return false
        }

        val tmpZip = dest.resolveSibling("runtime-download-${System.nanoTime()}.zip")
        try {
            if (!fetcher.download(assetUrl, tmpZip)) {
                LOG.warn("failed to download runtime $assetUrl")
                return false
            }
            val actual = RuntimeArchive.sha256(tmpZip)
            if (!actual.equals(expected, ignoreCase = true)) {
                LOG.warn("runtime sha256 mismatch: expected $expected, got $actual ($assetUrl)")
                return false
            }
            Files.createDirectories(dest)
            RuntimeArchive.unzip(tmpZip, dest)
            return isPresent(dest)
        } catch (e: Exception) {
            LOG.warn("failed to provision runtime", e)
            return false
        } finally {
            runCatching { Files.deleteIfExists(tmpZip) }
        }
    }

    /** 运行时根是否就绪（node + dsh 均已解压到位）。 */
    fun isPresent(dest: Path): Boolean =
        Files.isRegularFile(dest.resolve("node").resolve(Platform.current().nodeBinName)) &&
            Files.isRegularFile(dest.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"))
}
