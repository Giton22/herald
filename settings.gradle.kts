rootProject.name = "herald"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The JetBrains Runtime's API (the desktop app's title bar, corners, window moves) and its Chromium's (JCEF),
        // published only here.
        exclusiveContent {
            forRepository { maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
            filter {
                includeModule("com.jetbrains", "jbr-api")
                includeModule("org.jetbrains.intellij.deps.jcef", "jcef")
            }
        }
    }
}

include(":androidApp")
include(":desktopApp")
include(":shared:core")
include(":shared:designsystem")
include(":shared:ui")
include(":shared:wearlink")
include(":wearApp")
