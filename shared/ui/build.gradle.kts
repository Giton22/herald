plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "dev.hermeskotlin.ui"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        // The RTL layout harness renders the real components under Robolectric, so the host test needs the
        // module's merged Android resources (the app's fonts and theme) rather than stubs.
        withHostTest { isIncludeAndroidResources = true }
    }
    // The desktop app (experimental). Its lambdas are compiled as classes, not made at run time: the app's warm-up
    // can load them ahead, so a page's first opening doesn't build them one by one on the UI thread.
    jvm {
        compilerOptions { freeCompilerArgs.add("-Xlambdas=class") }
    }

    sourceSets {
        // What Android and the desktop share: the selection menu, which reaches into Compose by JVM reflection.
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        commonMain.dependencies {
            api(projects.shared.core)
            api(projects.shared.designsystem)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.haze)
            implementation(libs.haze.blur)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            // The Health switch asks for Health Connect's permissions through its own screen.
            implementation(libs.androidx.health.connect)
            // Screens the agent draws in the chat (experiment), themed through Material's roles.
            implementation(libs.a2ui.compose.runtime)
            implementation(libs.a2ui.compose.ui)
            implementation(libs.a2ui.material3)
            implementation(libs.androidx.material3)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.okhttp)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource.okhttp)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The desktop's own menus, driven with a mouse.
        jvmMain.dependencies {
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
        // Rendered RTL layout tests: Robolectric hosts the real Compose measurement and pixels.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            implementation(libs.robolectric)
            implementation(libs.compose.ui.test.junit4)
        }
    }
}

// Lint only ever gets this module's test sources, and the app's lint ignores those (see androidApp).
tasks.matching { it.name == "lintAnalyzeAndroidHostTest" }.configureEach { enabled = false }

// `-Drtl.geometry=<dir>` asks the RTL layout tests to write their geometry and pixels there.
providers.systemProperty("rtl.geometry").orNull?.let { dir ->
    tasks.withType<Test>().configureEach { systemProperty("rtl.geometry", dir) }
}
