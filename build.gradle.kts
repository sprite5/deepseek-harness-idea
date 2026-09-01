import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.0.21"
    // 经典插件线：2.x（org.jetbrains.intellij.platform）未发布到本网络可达的
    // Gradle 插件门户可见范围，且 DSL 与 1.x 不兼容；1.17.4 为本环境可解析的最新稳定版。
    // 升级到 2.x 作为后续改进项（见 docs/DESIGN.md §3.1）。
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.deepseek.harness"
version = "0.1.7"

// v0.1.7 起 universal plugin zip（无平台后缀，跨所有 OS/arch）。
// gradle-intellij-plugin 默认在产物名里追加 host 平台（"-win"/"-macos"/"-linux"），
// 我们覆盖 archivesName 让产物名始终是 universal。
project.archivesName.set("dsh-idea-simple-universal")

repositories {
    mavenCentral()
}

val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2024.1.7")

// 当前主机平台（仅日志用；v0.1.7 universal zip 已覆盖所有 OS/arch，主机不再决定产物分支）
val hostOs: String = when {
    System.getProperty("os.name").lowercase().contains("win") -> "win"
    System.getProperty("os.name").lowercase().contains("mac") || System.getProperty("os.name").lowercase().contains("darwin") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    else -> "win"
}
val hostArch: String = if (System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }) "arm64" else "x64"

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // JCEF 在 2026.2（build 262）起从平台核心拆分为内置插件 com.intellij.modules.jcef：
    // 其模块声明为 public 可见性，运行时无需在 plugin.xml 声明依赖即可解析类；
    // 前向编译检查（-PplatformVersion=2026.2）时把该内置插件的 lib jars 加入编译 classpath 验证 API 兼容。
    if (platformVersion.startsWith("2026")) {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME") ?: (System.getProperty("user.home") + "/.gradle")
        val sdkCache = file("$gradleUserHome/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/$platformVersion")
        compileOnly(
            fileTree(sdkCache) {
                include("*/ideaIC-$platformVersion/plugins/jcef-plugin/lib/**/*.jar")
            }
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 前向编译检查（-PplatformVersion=2026.2）时，新版平台自带的 Kotlin 模块（如 fleet.*）
        // metadata 版本高于本工程 Kotlin 2.0.21，需跳过 metadata 版本校验（仅检查我们的源码，
        // 不涉及平台内部 Kotlin 类；见 docs/PROJECT_NOTES.md §1）
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Step 5：运行时 bundle 作为插件资源参与打包（build/plugin-runtime/，由 bundleRuntime 产出）
sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("plugin-runtime"))
    }
}

intellij {
    // 目标平台：IntelliJ IDEA Community 2024.1+（与 PRD 一致）
    // 支持 -PplatformVersion=2026.2 做前向兼容编译检查（见 docs/PROJECT_NOTES.md）
    version.set(platformVersion)
    type.set("IC")
    // JCEF：2024.1 内核自带（app-client.jar）；2026.2 起为内置插件，见上方 dependencies 条件编译 classpath
    plugins.set(emptyList())
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        // 2026.2 (build 262) 起兼容范围放宽；2026-08-20 用户实测 IDEA 2026.2 安装报
        // "requires build251.* or older"，故 251.* → 262.*（含前向编译验证，见 DESIGN §3.1）
        untilBuild.set("262.*")
    }

    // 跳过 searchable options 构建（需要无头启动 IDE，CI/沙箱中不稳定）
    buildSearchableOptions {
        enabled = false
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    // Step 2：构建 universal dsh 树（scripts/build-dsh.mjs；不含 node，运行时用系统 node）。
    // 单一 zip 覆盖 win-x64/arm64 + macos-x64/arm64 + linux-x64/arm64 全平台用户；
    // 运行时由 process.platform/arch 选对应 native（见 Platform.kt）。
    register("buildDsh", Exec::class) {
        description = "Build the universal dsh tree (@deepseek-ai/dsh + dsh-mobile-hanui); no node bundled; covers all OS/arch"
        group = "build"
        val script = rootProject.file("scripts/build-dsh.mjs")
        val outputDir = rootProject.file("build/dsh")
        val bundleZip = rootProject.file("build/dsh-universal.zip")
        inputs.file(script)
        outputs.dir(outputDir)
        outputs.file(bundleZip)
        commandLine(
            "node", script.absolutePath,
            "--output", outputDir.absolutePath,
            "--bundle"
        )
    }

    // Step 5：把 universal dsh zip 打进插件资源（重命名为 dsh-bundle.zip，由 DshHomeManager.DSH_BUNDLE_RESOURCE 读取）。
    // 先清空 plugin-runtime/ 旧残留（如上一版的 runtime-bundle.zip / dsh-<os>-<arch>.zip），避免被 sourceSets 一并打入新插件造成体积膨胀。
    register("bundleDsh", Copy::class) {
        description = "Package the universal dsh tree as dsh-bundle.zip into plugin resources (any host: win/macos/linux)"
        group = "build"
        // 若已有 build/dsh-universal.zip，直接复用既有离线包，避免每次全量 buildDsh 联网
        val bundle = rootProject.file("build/dsh-universal.zip")
        if (!bundle.exists()) {
            dependsOn("buildDsh")
        }
        val dest = rootProject.layout.buildDirectory.dir("plugin-runtime")
        inputs.file(bundle)
        outputs.dir(dest)
        doFirst { dest.get().asFile.deleteRecursively() }
        from(bundle) { rename { "dsh-bundle.zip" } }
        into(dest)
    }

    // 打包资源前先确保 dsh bundle 就位（若已构建过；未构建时跳过以免拖慢纯代码构建）
    processResources {
        dependsOn("bundleDsh")
    }
}
