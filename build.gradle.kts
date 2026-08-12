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
        bundledPlugin("com.intellij.database")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.spring")
        bundledPlugin("org.jetbrains.plugins.yaml")
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
            <p>MyBatis Assistant provides conservative MyBatis navigation, inspection, and incremental semantic models for IntelliJ IDEA.</p>
            <p>当前开发预览版提供双向精确导航、XML/Java 引用、参数路径与 ResultMap 属性解析、TypeAlias 引用、保守检查、安全 Quick Fix 与原生重命名、可增量失效的符号化动态 SQL 编译和字符级 source map、OGNL 语言支持、可选 SQL PSI、方言、异步数据库元数据、表列补全与低误报 schema 检查、带全量预览和稳定生成区的数据库代码生成，以及保守转换、幂等格式化、日志 SQL 还原、受控执行与 JUnit 测试骨架。</p>
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
        }
    }

    fun registerScopedCoverage(
        taskName: String,
        includes: List<String>,
        minimum: String,
    ) = register<JacocoCoverageVerification>(taskName) {
        dependsOn(test)
        executionData(layout.buildDirectory.file("jacoco/test.exec"))
        classDirectories.setFrom(layout.buildDirectory.dir("instrumented/instrumentCode").map {
            fileTree(it).apply {
                includes.forEach(::include)
            }
        })
        sourceDirectories.setFrom(files("src/main/java"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    this.minimum = minimum.toBigDecimal()
                }
            }
        }
    }

    val coreCoverage = registerScopedCoverage(
        "jacocoCoreCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/index/**",
            "io/github/ns3154/mybatisassistant/model/**",
            "io/github/ns3154/mybatisassistant/resolve/**",
            "io/github/ns3154/mybatisassistant/dynamic/**",
            "io/github/ns3154/mybatisassistant/ognl/**",
        ),
        "0.85",
    )
    val sqlDatabaseCoverage = registerScopedCoverage(
        "jacocoSqlDatabaseCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/sql/**",
            "io/github/ns3154/mybatisassistant/database/MyBatis*",
            "io/github/ns3154/mybatisassistant/inspection/MyBatisSqlSchemaInspection*",
        ),
        "0.85",
    )
    val databaseAdapterCoverage = registerScopedCoverage(
        "jacocoDatabaseAdapterCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/database/intellij/DatabaseToolsMetadataProvider*",
            "io/github/ns3154/mybatisassistant/database/intellij/DatabaseToolsMetadataInvalidationService*",
            "io/github/ns3154/mybatisassistant/database/intellij/DatabaseToolsSqlExecutionBackend*",
        ),
        "0.70",
    )
    val generatorCoverage = registerScopedCoverage(
        "jacocoGeneratorCoverageVerification",
        listOf("io/github/ns3154/mybatisassistant/generator/**"),
        "0.85",
    )
    val methodSqlCoverage = registerScopedCoverage(
        "jacocoMethodSqlCoverageVerification",
        listOf("io/github/ns3154/mybatisassistant/methodsql/**"),
        "0.85",
    )
    val logSqlCoverage = registerScopedCoverage(
        "jacocoLogSqlCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/sqltool/log/**",
            "io/github/ns3154/mybatisassistant/sqltool/format/**",
            "io/github/ns3154/mybatisassistant/sqltool/conversion/**",
            "io/github/ns3154/mybatisassistant/sqltool/execution/**",
            "io/github/ns3154/mybatisassistant/sqltool/testgen/**",
        ),
        "0.85",
    )
    val frameworkAnnotationsCoverage = registerScopedCoverage(
        "jacocoFrameworkAnnotationsCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/inspection/MyBatisInvalidAnnotationParameterInspection*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisAnnotationParameterReference*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisAnnotationSqlSupport*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisJavaReferenceContributor*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisParameterPathResolver*",
            "io/github/ns3154/mybatisassistant/sql/intellij/MyBatisSqlCompletionContributor*",
            "io/github/ns3154/mybatisassistant/sqltool/annotation/**",
            "io/github/ns3154/mybatisassistant/spring/**",
        ),
        "0.85",
    )

    check {
        dependsOn(
            "jacocoTestCoverageVerification",
            coreCoverage,
            sqlDatabaseCoverage,
            databaseAdapterCoverage,
            generatorCoverage,
            methodSqlCoverage,
            logSqlCoverage,
            frameworkAnnotationsCoverage,
        )
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
