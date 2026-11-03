plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// A trusted app release supplies the exact reviewed upstream commit; local builds fail closed.
val generateScreenPluginRelease = tasks.register("generateScreenPluginRelease") {
    val revision = providers.gradleProperty("screenPluginRevision").orElse("")
    inputs.property("revision", revision)
    val output = layout.buildDirectory.dir("generated/screenPluginRelease")
    outputs.dir(output)
    doLast {
        val sha = revision.get()
        require(sha.isEmpty() || sha.matches(Regex("[0-9a-fA-F]{40}"))) { "screenPluginRevision must be a full 40-character commit SHA" }
        val spec = if (sha.isEmpty()) "null" else "GitPluginSpec(\"herald-screen\", \"https://github.com/Giton22/kyllene.git#plugins/herald-screen\", \"$sha\")"
        val file = output.get().file("dev/hermeskotlin/core/plugins/ScreenPluginRelease.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.hermeskotlin.core.plugins\nobject ScreenPluginRelease { val spec: GitPluginSpec? = $spec }\n")
    }
}

kotlin {
    android {
        namespace = "dev.hermeskotlin.core"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }

    // iOS targets are added here once the Android app is in shape.

    // The desktop app (experimental).
    jvm()

    sourceSets {
        commonMain.get().kotlin.srcDir(generateScreenPluginRelease)
        // What Android and the desktop share: the JDK's crypto, text and time, jsoup and OkHttp.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jsoup)
                implementation(libs.markdown.parser)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.jna.platform)
            implementation(libs.desktop.webrtc)
            implementation(libs.dbus.core)
            if (System.getProperty("os.name").startsWith("Linux")) {
                val classifier = when (System.getProperty("os.arch")) {
                    "amd64", "x86_64" -> "linux-x86_64"
                    "aarch64" -> "linux-aarch64"
                    else -> null
                }
                classifier?.let { runtimeOnly("${libs.desktop.webrtc.get()}:$it") }
                implementation(libs.dbus.unix)
                implementation(libs.junixsocket.common)
                runtimeOnly(libs.junixsocket.native)
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(projects.shared.wearlink)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.koin.core)
            implementation(libs.room.runtime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jsoup)
            implementation(libs.markdown.parser)
            implementation(libs.koin.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.webrtc.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Lint only ever gets this module's test sources, and the app's lint ignores those (see androidApp).
tasks.matching { it.name == "lintAnalyzeAndroidHostTest" }.configureEach { enabled = false }
