// A standalone JVM build that renders design concepts to PNG, headless, with the app's own UI stack.
// Run from the repo root: ./gradlew -p design-lab render   (PNGs land in design-lab/out)
plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.unstyled)
    implementation(libs.icons.lucide)
    implementation(libs.haze)
    implementation(libs.haze.blur)
}

tasks.register<JavaExec>("render") {
    group = "design lab"
    description = "Renders every concept screen to design-lab/out"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.hermeskotlin.lab.RenderKt")
    args(layout.projectDirectory.dir("out").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
