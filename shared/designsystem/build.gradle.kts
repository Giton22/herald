plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "dev.hermeskotlin.designsystem"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.unstyled)
            api(libs.compose.unstyled.colored.indication)
            api(libs.icons.lucide)
            implementation(libs.markdown.renderer)
            implementation(libs.highlights)
            implementation(libs.compose.ui.tooling.preview)
        }
        androidMain.dependencies {
            implementation(libs.jlatexmath.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
