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
        // The catalogue ships as this module's assets; the KMP library plugin leaves Android resources off
        // unless asked, and then every stringResource() would find nothing at run time.
        androidResources { enable = true }
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
            implementation(compose.components.resources)
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
            implementation(libs.filekit.dialogs.get().toString()) {
                if (System.getProperty("os.name").startsWith("Linux")) {
                    exclude(group = "com.github.hypfvieh", module = "dbus-java-transport-native-unixsocket")
                }
            }
            implementation(libs.pdfbox)
        }
        // The bot screen's viewer page: the app's own page, built from noVNC (see vendor/screen-viewer).
        // It travels in the resources because a browser will only load it from a file: address, which is
        // the one the gateway accepts for a display stream.
        jvmMain.get().resources.srcDir(rootProject.file("vendor/screen-viewer/dist"))
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

// The screens' words live in composeResources: values/ is English, values-de German, and a new language is a
// folder beside them. Both apps read from it; docs/i18n.md says what is left in code.
compose.resources {
    packageOfResClass = "dev.hermeskotlin.ui.resources"
}

// The viewer's third-party notices travel with it. The page carries noVNC's core, and noVNC's core
// carries the pako it ships with, so the page is covered by their licences and has to say so: the
// notices sit in the viewer's dist, which is what the apps package. A build that would package the
// page without them stops here.
val verifyScreenViewerNotices = tasks.register("verifyScreenViewerNotices") {
    val dist = rootProject.file("vendor/screen-viewer/dist")
    val notices = listOf("LICENSE.noVNC.txt", "AUTHORS.noVNC.txt", "LICENSE.pako.txt")
    inputs.dir(dist).withPropertyName("viewer")
    outputs.upToDateWhen { false }
    doLast {
        val missing = notices.filter { !File(dist, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The viewer would ship without ${missing.joinToString()}. " +
                    "Run vendor/screen-viewer/build.sh and commit what it writes.",
            )
        }
    }
}

// The desktop app's resources are the viewer's dist, so the jar it ships carries the notices.
tasks.matching { it.name == "jvmJar" }.configureEach { dependsOn(verifyScreenViewerNotices) }
// `-Drtl.geometry=<dir>` asks the RTL layout tests to write their geometry and pixels there.
providers.systemProperty("rtl.geometry").orNull?.let { dir ->
    tasks.withType<Test>().configureEach { systemProperty("rtl.geometry", dir) }
}
