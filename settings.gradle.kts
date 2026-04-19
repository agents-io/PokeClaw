pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content {
                // Tell Gradle NOT to look for Qualcomm packages on Aliyun
                excludeGroupByRegex("com\\.qualcomm.*")
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content {
                // Tell Gradle NOT to look for Qualcomm packages on Aliyun
                excludeGroupByRegex("com\\.qualcomm.*")
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PokeClaw"
include(":app")