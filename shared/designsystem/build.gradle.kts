plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "dev.hermeskotlin.designsystem"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        // The RTL layout harness renders the real components under Robolectric, so the host test needs the
        // module's merged Android resources (the bundled Geist fonts) rather than stubs.
        withHostTest { isIncludeAndroidResources = true }
        // For the bundled Geist fonts in res/font (license: GEIST-OFL.txt).
        androidResources { enable = true }
    }
    // The desktop app (experimental). Lambdas as classes, which the app's warm-up loads ahead (see shared/ui).
    jvm {
        compilerOptions { freeCompilerArgs.add("-Xlambdas=class") }
    }

    sourceSets {
        jvmMain {
            // The same Geist files, loaded from the classpath.
            resources.srcDir("src/androidMain/res/font")
            dependencies {
                implementation(libs.jlatexmath)
            }
        }
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
        // Rendered RTL layout tests: Robolectric hosts the real Compose measurement and pixels.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.robolectric)
            implementation(libs.compose.ui.test.junit4)
        }
        // The table scrollbar only draws on the desktop, so its geometry is rendered and measured on the desktop
        // target. Test-only, like the host-test entries above.
        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Lint only ever gets this module's test sources, and the app's lint ignores those (see androidApp).
tasks.matching { it.name == "lintAnalyzeAndroidHostTest" }.configureEach { enabled = false }

// `-Drtl.geometry=<dir>` asks the RTL layout tests to write their geometry and pixels there.
providers.systemProperty("rtl.geometry").orNull?.let { dir ->
    tasks.withType<Test>().configureEach { systemProperty("rtl.geometry", dir) }
}
