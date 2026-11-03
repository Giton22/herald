import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The desktop app: an experiment that runs the shared screens in a window. Like the Android app, a local build
// is 0.1.0; -PappVersion sets it. A release also sets -PreleasesRepo=owner/name, where the app looks for updates.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(projects.shared.ui)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.koin.core)
            // A chat window's own view models.
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jbr.api)
            // Chromium for widgets and plugin pages; the runtime brings it (see libs.versions.toml).
            compileOnly(libs.jcef)
            // Windows' global hotkey for the quick entry.
            implementation(libs.jna.platform)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // JCEF's plain classes (its cookie), which tests on any JDK can load.
            implementation(libs.jcef)
        }
    }
}

// Compose copies common app resources into every native app image and installer.
val desktopNoticeResources = layout.buildDirectory.dir("generated/desktopNoticeResources")
val noticeFile = rootProject.file("THIRD_PARTY_NOTICES.md")
check(noticeFile.isFile) { "Missing THIRD_PARTY_NOTICES.md" }
val prepareDesktopNotice = tasks.register<Sync>("prepareDesktopNotice") {
    from(noticeFile)
    into(desktopNoticeResources.map { it.dir("common") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareDesktopNotice) }

compose.desktop {
    application {
        mainClass = "dev.hermeskotlin.desktop.MainKt"
        // Installers need a JetBrains Runtime SDK with JCEF (jpackage, and Chromium for widgets); Android Studio's
        // runtime, which Gradle often runs on, has neither. `run` uses it too.
        providers.environmentVariable("HERALD_PACKAGING_JDK").orNull?.let { javaHome = it }
        jvmArgs += "-Dherald.version=$appVersion"
        if (releasesRepo.isNotEmpty()) jvmArgs += "-Dherald.releasesRepo=$releasesRepo"
        // Herald sits in the tray for hours: G1 hands an idle heap back to the system each minute (JEP 346),
        // and the many equal strings of chat JSON share their bytes.
        jvmArgs += listOf("-XX:G1PeriodicGCInterval=60000", "-XX:+UseStringDeduplication")
        // JNA (DPAPI, the global hotkey), Skia and JCEF's Chromium load native code, which newer JDKs warn about and
        // will block.
        jvmArgs += "--enable-native-access=ALL-UNNAMED,jcef"

        // The installers (packageRelease…) leave out the library code the app never reaches. Names are kept:
        // renaming would save little more, and code that looks classes up by name (Warmup, Compose's text menu)
        // would need a rule for each.
        buildTypes.release.proguard {
            obfuscate = false
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            appResourcesRootDir.set(desktopNoticeResources)
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Kyllene"
            packageVersion = appVersion
            description = "Kyllene for Hermes Agent"
            vendor = "Kyllene"
            // The JDK modules the app reaches: the default set misses DPAPI's JNA, SQLite and the JDK's own HTTP bits.
            // jdk.accessibility carries the Java Access Bridge that Windows screen readers (NVDA, JAWS) talk to; jcef
            // the Chromium that shows widgets and plugin pages, with its own files.
            modules("java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.accessibility", "jcef")
            // D-Bus EXTERNAL authentication reads UnixSystem from this module.
            if (System.getProperty("os.name").startsWith("Linux")) modules("jdk.security.auth")
            windows {
                menuGroup = "Kyllene"
                // Keeps upgrades in place: one Kyllene in Programs and Features, whatever the version.
                upgradeUuid = "5b0f3c8e-6f2a-4d0e-9a41-3c7d2e8b9f10"
                iconFile.set(project.file("icons/herald.ico"))
                // Herald keeps its data in the user's profile, so it installs there too, with no admin prompt.
                perUserInstall = true
                shortcut = true
            }
            macOS {
                bundleID = "dev.hermeskotlin.herald"
                // macOS takes no version below 1.0.0 for an app; the app itself still shows its real one.
                if (appVersion.startsWith("0.")) packageVersion = "1.0.0"
                // herald:// links open in Herald (Windows registers them at launch; see WindowsToasts).
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Kyllene link</string>
                                <key>CFBundleURLSchemes</key>
                                <array><string>herald</string></array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
                iconFile.set(project.file("icons/herald.icns"))
            }
            linux {
                iconFile.set(project.file("icons/herald.png"))
                shortcut = true
                appCategory = "Network"
            }
        }
    }
}

// The installer bundles the runtime it's made with. Herald's title bar is drawn through JetBrains Runtime's window
// decorations; any other JDK shows the OS's own bar over the app, so an installer made with one stops here.
afterEvaluate {
    val runtime = File(compose.desktop.application.javaHome, "release")
    val needsJbr: (Task) -> Unit = {
        it.doFirst {
            check(runtime.isFile && runtime.readText().contains("IMPLEMENTOR=\"JetBrains") && runtime.readText().contains(" jcef")) {
                "The installer needs JetBrains Runtime (a JBR SDK with JCEF, jbrsdk_jcef, which has jpackage), " +
                    "not ${runtime.parent}: set HERALD_PACKAGING_JDK to one."
            }
        }
    }
    // jlink comes first and would fail on the missing jcef module with a less helpful message.
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach(needsJbr)
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
        dependsOn(prepareDesktopNotice)
        needsJbr(this)
    }
}

// Compose trims the runtime with jlink's --strip-native-commands, which drops each module's bin/ files. For jcef those
// are Chromium's own: its helper process, ICU data, resource packs and locales, without which it crashes on start. They
// go back in from the packaging JDK, by the module's own list of them.
afterEvaluate {
    val jdk = File(compose.desktop.application.javaHome)
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
        val runtime = destinationDir
        doLast {
            val jmod = File(jdk, "jmods/jcef.jmod")
            if (!jmod.isFile) return@doLast
            val tool = File(jdk, if (System.getProperty("os.name").startsWith("Windows")) "bin/jmod.exe" else "bin/jmod")
            val list = ProcessBuilder(tool.path, "list", jmod.path).redirectErrorStream(true).start()
            val entries = list.inputStream.bufferedReader().readLines()
            check(list.waitFor() == 0) { "Couldn't list jcef.jmod:\n${entries.joinToString("\n")}" }
            val bin = runtime.get().asFile.resolve("bin")
            entries.filter { it.startsWith("bin/") }.map { it.removePrefix("bin/") }
                // The remote-process server is for JCEF's out-of-process mode, which Herald doesn't use.
                .filterNot { it.startsWith("cef_server") }
                .forEach { File(jdk, "bin/$it").copyTo(File(bin, it), overwrite = true) }
            // On Linux Chromium's helper process is a native-lib entry, lib/jcef_helper, and jlink writes those without
            // the executable bit: Chromium then can't start it, and no widget or plugin page renders in the .deb.
            val helper = runtime.get().asFile.resolve("lib/jcef_helper")
            if (helper.isFile) {
                check(helper.setExecutable(true, false) && helper.canExecute()) { "Couldn't make $helper executable" }
            }
        }
    }
}

// An installed Herald on Windows reads its classes from a shared archive beside the app (AppCDS), so a launch skips
// reading and checking them again: about 2.1s to the window becomes 1.2s. Herald makes the archive itself, in the
// background, after the first launch and after each update (see ClassArchive). Only Windows: Herald installs into the
// user's own profile there, where it can write; on macOS and Linux the app's folder is read-only. Only the installer:
// `run` has no $APPDIR. Until the archive exists, the JVM goes on without it.
if (System.getProperty("os.name").startsWith("Windows")) {
    afterEvaluate {
        tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
            launcherJvmArgs.add("-XX:SharedArchiveFile=\$APPDIR/herald.jsa")
        }
        // Herald's archive sits on top of the JDK's own (classes.jsa), which jlink leaves out of the trimmed runtime
        // unless asked, and Compose doesn't ask. The runtime has no java.exe to make one with, so the packaging JDK's
        // launcher is borrowed for it: it starts the runtime's own JVM, which archives the runtime's own modules.
        // On the runtime image itself, which both the app folder and the installers copy.
        val launcher = File(compose.desktop.application.javaHome, "bin/java.exe")
        tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
            val runtime = destinationDir
            doLast {
                val bin = runtime.get().asFile.resolve("bin")
                val java = File(bin, "java.exe")
                launcher.copyTo(java, overwrite = true)
                try {
                    val dump = ProcessBuilder(java.path, "-Xshare:dump").redirectErrorStream(true).start()
                    val output = dump.inputStream.bufferedReader().readText()
                    check(dump.waitFor() == 0 && File(bin, "server/classes.jsa").isFile) { "No CDS archive for the runtime:\n$output" }
                } finally {
                    java.delete()
                }
            }
        }
    }
}

// ProGuard reads junixsocket's Java 7 fallback classes, whose enclosing-method metadata is incompatible with
// shrinking. Keep its matching multi-release Java/native libraries intact, but analyze them when shrinking the app.
if (System.getProperty("os.name").startsWith("Linux")) {
    val junixsocketVersion = libs.versions.junixsocket.get()
    afterEvaluate {
        val shrinker = tasks.named<AbstractProguardTask>("proguardReleaseJars").get()
        // Snapshot the sources: inputFiles.from is a live set, so retaining it would make this collection recursive.
        val original = files(shrinker.inputFiles.from.toList())
        val nativeJars = original.filter {
            it.name.startsWith("junixsocket-common-") || it.name.startsWith("junixsocket-native-common-")
        }
        val expected = setOf("junixsocket-common-$junixsocketVersion.jar", "junixsocket-native-common-$junixsocketVersion.jar")
        val rules = layout.buildDirectory.file("secret-transport/native-libraries.pro")
        val prepare = tasks.register("prepareSecretTransportLibraries") {
            inputs.files(nativeJars)
            inputs.property("expectedJars", expected)
            outputs.file(rules)
            doLast {
                val jars = nativeJars.files
                check(jars.size == 2 && jars.map { it.name }.toSet() == expected) {
                    "Expected matching junixsocket libraries $expected, found ${jars.map { it.name }}"
                }
                val file = rules.get().asFile
                file.parentFile.mkdirs()
                file.writeText(jars.sortedBy { it.name }.joinToString("\n", postfix = "\n") {
                    "-libraryjars \"${it.invariantSeparatorsPath}\""
                })
            }
        }
        shrinker.inputFiles.setFrom(original - nativeJars)
        shrinker.inputs.files(nativeJars)
        shrinker.configurationFiles.from(prepare)
        val output = shrinker.destinationDir
        shrinker.doLast {
            nativeJars.forEach { it.copyTo(output.get().asFile.resolve(it.name), overwrite = true) }
        }
    }
}
