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
version = "0.1.3"

repositories {
    mavenCentral()
}

val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2024.1.7")

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

    // Step 2：构建内嵌运行时（scripts/build-runtime.ps1，见 docs/DESIGN.md §3.2）
    register("buildRuntime", Exec::class) {
        description = "Build the embedded DSH runtime (Node + @deepseek-ai/dsh) into build/runtime"
        group = "build"
        val script = rootProject.file("scripts/build-runtime.ps1")
        val outputDir = rootProject.file("build/runtime")
        inputs.file(script)
        outputs.dir(outputDir)
        commandLine(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            script.absolutePath, "-OutputDir", outputDir.absolutePath, "-Bundle"
        )
    }

    // Step 5：运行时打入插件资源（buildRuntime 产物压缩包 → build/plugin-runtime/）
    register("bundleRuntime", Copy::class) {
        description = "Package the built runtime as runtime-bundle.zip into plugin resources"
        group = "build"
        dependsOn("buildRuntime")
        val bundle = rootProject.file("build/runtime-bundle.zip")
        val dest = rootProject.layout.buildDirectory.dir("plugin-runtime")
        inputs.file(bundle)
        outputs.dir(dest)
        from(bundle) { rename { "runtime-bundle.zip" } }
        into(dest)
    }

    // 打包资源前先确保运行时 bundle 就位（若已构建过；未构建时跳过以免拖慢纯代码构建）
    processResources {
        dependsOn("bundleRuntime")
    }
}
