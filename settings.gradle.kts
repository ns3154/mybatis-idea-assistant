import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "mybatis-idea-assistant"

pluginManagement {
    plugins {
        id("org.cyclonedx.bom") version "3.4.1"
        id("org.jetbrains.intellij.platform") version "2.18.1"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
