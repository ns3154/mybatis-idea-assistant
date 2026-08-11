import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
    checkstyle
    jacoco
    id("org.jetbrains.intellij.platform")
}

group = "io.github.ns3154.mybatisassistant"
version = providers.gradleProperty("pluginVersion").orElse("0.1.0-SNAPSHOT").get()

val pluginVerifierIdeVersion = providers.gradleProperty("pluginVerifierIdeVersion").orElse("2026.1")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

checkstyle {
    toolVersion = "13.10.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isShowViolations = true
    maxWarnings = 0
}

jacoco {
    toolVersion = "0.8.15"
}

dependencyLocking {
    lockAllConfigurations()
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
    // 开发实例使用独立、可清理的构建沙箱，避免继承本机 IDEA 与历史人工验收状态。
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")

    pluginConfiguration {
        id = "io.github.ns3154.mybatis-idea-assistant"
        name = "MyBatis Assistant"
        version = project.version.toString()
        description = """
            <p>MyBatis Assistant connects Java mapper methods with MyBatis XML statements and conservatively reports missing statements.</p>
            <p>当前开发预览版提供双向安全导航、类型化解析，以及已有 Mapper XML 中缺失 statement 的保守检查。</p>
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
            create(IntelliJPlatformType.IntellijIdea, pluginVerifierIdeVersion)
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
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
        finalizedBy("jacocoTestReport")
    }

    named<JacocoReport>("jacocoTestReport") {
        dependsOn(test)
        // 平台测试运行的是 IntelliJ 表单插桩后的插件 JAR，报告必须使用同一份 class。
        classDirectories.setFrom(layout.buildDirectory.dir("instrumented/instrumentCode"))
        reports {
            xml.required = true
            html.required = true
            csv.required = false
        }
    }

    named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(test)
        // 平台测试运行的是 IntelliJ 表单插桩后的插件 JAR，校验必须使用同一份 class。
        classDirectories.setFrom(layout.buildDirectory.dir("instrumented/instrumentCode"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.70".toBigDecimal()
                }
            }
            rule {
                includes = listOf(
                    "io.github.ns3154.mybatisassistant.index.*",
                    "io.github.ns3154.mybatisassistant.model.*",
                    "io.github.ns3154.mybatisassistant.resolve.*",
                )
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = "0.85".toBigDecimal()
                }
            }
        }
    }

    check {
        dependsOn("jacocoTestCoverageVerification")
    }

    withType<Checkstyle>().configureEach {
        reports {
            xml.required = true
            html.required = true
        }
    }

    named<PrepareSandboxTask>("prepareTestSandbox") {
        // 2026.1.4 的 Vue 插件在轻量测试沙箱中会按完整 IDE 目录结构定位资源，
        // 与本插件无关且会导致测试框架启动失败，因此仅在测试沙箱禁用。
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }
}
