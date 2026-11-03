plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// What the phone and the watch say to each other over the Wear OS Data Layer. Both apps depend on it, so a
// message one writes is one the other reads; it holds no Android or gateway code of its own.
kotlin {
    android {
        namespace = "dev.hermeskotlin.wearlink"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        // The apps build for JVM 11, and they inline WearJson's reified helpers: this module's bytecode must match.
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    // The desktop app (experimental).
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // InOrder.skipGaps waits out a call's gaps.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Lint only ever gets this module's test sources, and the app's lint ignores those (see androidApp).
tasks.matching { it.name == "lintAnalyzeAndroidHostTest" }.configureEach { enabled = false }
