import org.cyclonedx.gradle.BaseCyclonedxTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.jvm.tasks.Jar
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
    checkstyle
    jacoco
    id("org.cyclonedx.bom")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.ns3154.mybatisassistant"
version = providers.gradleProperty("pluginVersion").orElse("0.1.0-SNAPSHOT").get()

val pluginVerifierIdeVersion = providers.gradleProperty("pluginVerifierIdeVersion").orElse("2026.1")
val pluginVerifierProduct = providers.gradleProperty("pluginVerifierProduct").orElse("idea")

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
    testImplementation("com.h2database:h2:2.3.232")

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
            <p>MyBatis Assistant provides conservative MyBatis navigation, inspections, references, refactoring, dynamic SQL analysis, database metadata, code generation, and local SQL tools for IntelliJ IDEA.</p>
            <p>支持 Java、Kotlin K2、XML、OGNL、Spring、MyBatis-Plus/Flex/TkMapper、六类数据库方言、可选 Database Tools 与 Community JDBC。所有数据库生成与 MCP 写操作都先预览，再复核冲突并使用可撤销 IDE Command。</p>
            <p>默认离线；本地 MCP 默认关闭且只绑定 127.0.0.1，使用随机内存令牌和工具白名单。插件不包含遥测，不会把项目源码、SQL、数据库结构或凭据上传给维护者。</p>
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
            val product = pluginVerifierProduct.get()
            val type = when (product) {
                "idea" -> IntelliJPlatformType.IntellijIdea
                "android-studio" -> IntelliJPlatformType.AndroidStudio
                else -> throw GradleException("不支持的 Plugin Verifier 产品：$product")
            }
            create(type, pluginVerifierIdeVersion)
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginChannel")
            .map { listOf(it) }
            .orElse(listOf("default"))
        hidden = providers.gradleProperty("pluginHidden")
            .map(String::toBoolean)
            .orElse(false)
    }
}

tasks {
    named<Jar>("jar") {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
    }

    withType<BaseCyclonedxTask>().configureEach {
        // 去掉随机序列号和 CI 地址，并固定 VCS，保证同一版本与依赖锁下的 SBOM 可复现。
        includeBomSerialNumber.set(false)
        includeBuildSystem.set(false)
        externalReferences.set(listOf(ExternalReference().apply {
            type = ExternalReference.Type.VCS
            url = "https://github.com/ns3154/mybatis-idea-assistant"
        }))
        licenseChoice.set(LicenseChoice().apply {
            addLicense(License().apply {
                id = "Apache-2.0"
            })
        })
        doLast {
            // CycloneDX 的 timestamp 为可选字段；插件默认填当前时间，会破坏字节级可复现性。
            jsonOutput.orNull?.asFile?.takeIf(File::exists)?.let { output ->
                output.writeText(
                    output.readText(Charsets.UTF_8).replace(
                        Regex("""(?m)^\s*"timestamp"\s*:\s*"[^"]+",\R"""),
                        "",
                    ),
                    Charsets.UTF_8,
                )
            }
            xmlOutput.orNull?.asFile?.takeIf(File::exists)?.let { output ->
                output.writeText(
                    output.readText(Charsets.UTF_8).replace(
                        Regex("""(?m)^\s*<timestamp>[^<]*</timestamp>\R"""),
                        "",
                    ),
                    Charsets.UTF_8,
                )
            }
        }
    }

    named<CyclonedxDirectTask>("cyclonedxDirectBom") {
        // 插件 ZIP 不捆绑 IntelliJ SDK、测试依赖或 JDBC 驱动，只审计实际运行时依赖。
        includeConfigs.set(listOf("runtimeClasspath"))
        includeBuildEnvironment.set(false)
    }

    val verifyCyclonedxBom = register("verifyCyclonedxBom") {
        group = "verification"
        description = "验证可复现 CycloneDX SBOM 的版本、许可证和敏感字段边界"
        dependsOn("cyclonedxBom")
        val json = layout.buildDirectory.file("reports/cyclonedx/bom.json")
        val xml = layout.buildDirectory.file("reports/cyclonedx/bom.xml")
        val expectedPluginVersion = project.version.toString()
        inputs.files(json, xml)
        doLast {
            val jsonText = json.get().asFile.readText(Charsets.UTF_8)
            val xmlText = xml.get().asFile.readText(Charsets.UTF_8)
            check("\"bomFormat\" : \"CycloneDX\"" in jsonText) { "SBOM JSON 格式不正确" }
            check("\"version\" : \"$expectedPluginVersion\"" in jsonText) { "SBOM 插件版本不正确" }
            check("\"id\" : \"Apache-2.0\"" in jsonText) { "SBOM 未声明 Apache-2.0" }
            check("<id>Apache-2.0</id>" in xmlText) { "SBOM XML 未声明 Apache-2.0" }
            check("timestamp" !in jsonText && "<timestamp>" !in xmlText) {
                "SBOM 仍包含不可复现的生成时间"
            }
            check("serialNumber" !in jsonText && "serialNumber=" !in xmlText) {
                "SBOM 仍包含随机序列号"
            }
            val sensitiveNames = listOf("PRIVATE_KEY", "PRIVATE_KEY_PASSWORD", "PUBLISH_TOKEN")
            check(sensitiveNames.none { it in jsonText || it in xmlText }) {
                "SBOM 包含发布密钥字段"
            }
        }
    }

    val verifyLocalizedUserInterface = register("verifyLocalizedUserInterface") {
        group = "verification"
        description = "阻止插件生产 Java 源码新增硬编码中文字符串"
        val sourceRoots = listOf(
            "src/main/java/io/github/ns3154/mybatisassistant",
        )
        val sources = files(sourceRoots.map { fileTree(it) }).asFileTree.matching {
            include("**/*.java")
        }
        val repositoryRoot = rootDir
        inputs.files(sources)
        doLast {
            val chineseString = Regex("\"[^\"\\n]*[\\u3400-\\u9fff][^\"\\n]*\"")
            val offenders = sources.files.sorted().flatMap { source ->
                source.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    if (chineseString.containsMatchIn(line)) {
                        "${source.relativeTo(repositoryRoot)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            check(offenders.isEmpty()) {
                "用户界面存在未资源化中文字符串：\n${offenders.joinToString("\n")}"
            }
        }
    }

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
            "io/github/ns3154/mybatisassistant/inspection/MyBatisInvalidKotlinAnnotationParameterInspection*",
            "io/github/ns3154/mybatisassistant/inspection/MyBatisMissingKotlinStatementInspection*",
            "io/github/ns3154/mybatisassistant/kotlin/**",
            "io/github/ns3154/mybatisassistant/model/MyBatisFramework*",
            "io/github/ns3154/mybatisassistant/model/MyBatisMapperModel*",
            "io/github/ns3154/mybatisassistant/navigation/MyBatisKotlinMapperLineMarkerProvider*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisAnnotationParameterReference*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisAnnotationSqlSupport*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisJavaReferenceContributor*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisKotlinAnnotationSqlSupport*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisKotlinReferenceContributor*",
            "io/github/ns3154/mybatisassistant/reference/MyBatisParameterPathResolver*",
            "io/github/ns3154/mybatisassistant/sql/intellij/MyBatisSqlCompletionContributor*",
            "io/github/ns3154/mybatisassistant/sqltool/annotation/**",
            "io/github/ns3154/mybatisassistant/spring/**",
        ),
        "0.85",
    )
    val databaseCompatibilityCoverage = registerScopedCoverage(
        "jacocoDatabaseCompatibilityCoverageVerification",
        listOf("io/github/ns3154/mybatisassistant/database/jdbc/**"),
        "0.85",
    )
    val productizationCoverage = registerScopedCoverage(
        "jacocoProductizationCoverageVerification",
        listOf(
            "io/github/ns3154/mybatisassistant/mcp/**",
            "io/github/ns3154/mybatisassistant/settings/MyBatisAssistantSettings.class",
            "io/github/ns3154/mybatisassistant/settings/MyBatisAssistantSettings\$*.class",
            "io/github/ns3154/mybatisassistant/settings/MyBatisAssistantSettingsCodec.class",
            "io/github/ns3154/mybatisassistant/settings/MyBatisAssistantSettingsListener.class",
        ),
        "0.85",
    )

    check {
        dependsOn(
            verifyCyclonedxBom,
            verifyLocalizedUserInterface,
            "jacocoTestCoverageVerification",
            coreCoverage,
            sqlDatabaseCoverage,
            databaseAdapterCoverage,
            generatorCoverage,
            methodSqlCoverage,
            databaseCompatibilityCoverage,
            productizationCoverage,
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
