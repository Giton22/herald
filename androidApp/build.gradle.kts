import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// A release sets these from its tag (-PappVersion=0.2.0) and repo (-PreleasesRepo=owner/name); see
// .github/workflows/release.yml. A local build is 0.1.0 and never looks for updates.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

android {
    namespace = "dev.hermeskotlin.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The installed identity; the Kotlin packages keep their original dev.hermeskotlin name.
        applicationId = "dev.herald.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // 1.2.3 → 10203: each release installs over the last, as long as the tags only go up.
        val (major, minor, patch) = appVersion.substringBefore('-').split('.').map { it.toInt() }.plus(listOf(0, 0)).take(3)
        require(minor < 100 && patch < 100) { "appVersion $appVersion: minor and patch must stay under 100" }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        buildConfigField("String", "RELEASES_REPO", "\"$releasesRepo\"")
    }

    // Release signing comes from an untracked keystore.properties at the repo root
    // (storeFile, storePassword, keyAlias, keyPassword); without it release builds use the debug key.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    // A published APK signed with a debug key wouldn't install over the real one, so a release fails instead.
    if (keystore == null && releasesRepo.isNotEmpty()) error("releasesRepo is set but keystore.properties is missing")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CI runs lintDebug. The baseline holds the issues that were there when lint was added, so only new
    // ones fail the build; regenerate it with :androidApp:updateLintBaseline after fixing some.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = false
        checkDependencies = true
        // It only ever fires on the untracked local.properties that Android Studio writes on Windows.
        disable += "PropertyEscape"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // "Herald Dev": a debuggable build that installs beside the real app, with its own data and sign-in,
        // for trying work in progress on a phone without touching the installed Herald.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("debug")
        }
    }
}

dependencies {
    implementation(projects.shared.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // Links in replies open in a Custom Tab; App lock asks through BiometricPrompt (needs a FragmentActivity).
    implementation(libs.androidx.browser)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.koin.android)
    debugImplementation(libs.compose.ui.tooling)
}
