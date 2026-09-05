import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.deepseek.harness"
version = "0.1.8"

// v0.1.7 起 universal plugin zip（无平台后缀，跨所有 OS/arch）。
// gradle-intellij-plugin 默认产物名 = <plugin-name>-<version>.zip，无法直接通过
// archivesName 控制 buildPlugin 产物（Kotlin DSL 中 project.archivesName 在 Gradle 8+ 已弃用）。
// 改在 workflow 里 buildPlugin 后用 mv 把 dsh-idea-simple-0.1.7.zip 重命名为
// dsh-idea-simple-universal-0.1.7.zip，再上传 artifact。

repositories {
    mavenCentral()
}

val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("2024.1.7")
val dshVersion: String = when {
    project.hasProperty("dshVersion") -> project.property("dshVersion").toString()
    else -> rootProject.file("scripts/build-dsh.mjs").takeIf { it.isFile }?.readText()?.let { text ->
        Regex("""opt\('dsh-version',\s*'([^']+)'\)""").find(text)?.groupValues?.get(1)
    } ?: "0.1.2-rc.1"
}

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
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("plugin-runtime"))
    }
}

intellij {
    version.set(platformVersion)
    type.set("IC")
    plugins.set(emptyList())
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("262.*")
        dependsOn("bundleDsh")
        doLast {
            val targetDir = destinationDir.get().asFile
            println("patchPluginXml targetDir: ${targetDir.absolutePath}, dshVersion: $dshVersion")
            targetDir.walk().filter { it.name == "plugin.xml" }.forEach { file ->
                val text = file.readText()
                file.writeText(text.replace("@dshVersion@", dshVersion))
                println("patched file: ${file.absolutePath}")
            }
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

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

    register("bundleDsh", Copy::class) {
        description = "Package the universal dsh tree as dsh-bundle.zip into plugin resources"
        group = "build"
        dependsOn("buildDsh")
        val bundle = rootProject.file("build/dsh-universal.zip")
        val runtimeDest = rootProject.layout.buildDirectory.dir("plugin-runtime")
        inputs.file(bundle)
        outputs.dir(runtimeDest)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        doFirst { runtimeDest.get().asFile.deleteRecursively() }
        from(bundle) { rename { "dsh-bundle.zip" } }
        into(runtimeDest)
        from(bundle) { rename { "dsh-bundle.zip" } }
        into(rootProject.file("src/main/resources"))
    }

    // Copy bundle into instrumented classes dir so instrumentedJar includes it
    register("copyBundleToInstrumented", Copy::class) {
        description = "Copy dsh-bundle.zip into instrumented classes dir"
        group = "build"
        dependsOn("bundleDsh")
        val bundle = rootProject.file("src/main/resources/dsh-bundle.zip")
        val dest = rootProject.layout.buildDirectory.dir("instrumented/instrumentCode")
        inputs.file(bundle)
        outputs.dir(dest)
        from(bundle)
        into(dest)
    }

    processResources {
        dependsOn("bundleDsh")
        filesMatching("**/plugin.xml") {
            filter { line ->
                line.replace("@dshVersion@", dshVersion)
            }
        }
    }

    // Make instrumentedJar depend on copying bundle into instrumented classes dir
    afterEvaluate {
        tasks.getByName("instrumentedJar").dependsOn("copyBundleToInstrumented")
    }
}
