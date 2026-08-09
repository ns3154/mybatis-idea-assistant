import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    java
    id("org.jetbrains.intellij.platform")
}

group = "io.github.ns3154.mybatisassistant"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2026.1.4")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "io.github.ns3154.mybatis-idea-assistant"
        name = "MyBatis Assistant"
        version = project.version.toString()
        description = """
            <p>MyBatis Assistant connects Java mapper methods with MyBatis XML statements through safe indexed navigation.</p>
            <p>当前开发预览版聚焦 Mapper 接口、XML namespace 与 statement 之间的可靠语义关联。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "261"
            // 官方建议 243 及以上不再限制 until-build，避免 IDE 小版本升级后无法安装。
            untilBuild = provider { null }
        }

        vendor {
            name = "ns3154"
            url = "https://github.com/ns3154/mybatis-idea-assistant"
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    test {
        maxHeapSize = "2g"
        systemProperty("java.awt.headless", "true")
    }

    named<PrepareSandboxTask>("prepareTestSandbox") {
        // 2026.1.4 的 Vue 插件在轻量测试沙箱中会按完整 IDE 目录结构定位资源，
        // 与本插件无关且会导致测试框架启动失败，因此仅在测试沙箱禁用。
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }
}
