# ProGuard for the desktop installers (release build type): shrinking and optimising only, names kept (see
# build.gradle.kts). These keep what's reached only by name, by native code or through the runtime.

# Narrowing a method's declared types can pick a type it doesn't always return: okio's buffer() came out
# declared as RealBufferedSource, and the JVM refused it (VerifyError), so the app never reached the gateway.
-optimizations !method/specialization/returntype,!method/specialization/parametertype

# The app's own code, whole: Warmup links every class under dev/hermeskotlin/ by name, and Koin builds view models
# and screens it can't see used.
-keep class dev.hermeskotlin.** { *; }

# Compose's desktop text menu items are read by name (TextContextMenu.jvm.kt), and the selection's texts too
# (CommentSelection.jvm.kt).
-keepclassmembers class androidx.compose.foundation.text.contextmenu.** {
    public *** getLabel();
    public *** getEnabled();
    public *** getOnClick();
}
-keepclassmembers class androidx.compose.foundation.text.selection.SelectionState {
    public java.util.List getSelectableTexts();
}

# kotlinx.serialization: the generated serializers are found from their classes' companions.
-keepattributes InnerClasses,Signature,*Annotation*,EnclosingMethod
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Ktor finds its engine and plugins through ServiceLoader.
-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider
-dontwarn io.ktor.**

# OkHttp (Ktor's engine) and its optional TLS providers.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SQLite's bundled driver and Room: native code calls back into the driver's classes by name.
-keep class androidx.sqlite.** { *; }
-keep class androidx.room.** { *; }

# JNA (DPAPI, the global hotkey): structures and callbacks are reached through reflection and from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# JetBrains Runtime's API binds its interfaces to the runtime's own classes by name.
-keep class com.jetbrains.** { *; }

# Skiko loads its native library and calls back into these.
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# Coroutines' Swing main dispatcher, found through ServiceLoader.
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# Linux file dialogs (FileKit) go through the XDG desktop portal over dbus-java, which finds its transport through
# ServiceLoader and builds its messages and interfaces by reflection.
-keep class org.freedesktop.dbus.** { *; }
-keep class io.github.vinceglb.filekit.dialogs.platform.xdg.** { *; }

# TeX environments and defined commands (matrices, cases, \dfrac, \newcommand) expand through NewCommandMacro,
# loaded and invoked by name in JLaTeXMath's MacroInfo. Shrunk, they fall back to raw TeX.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}

# Optional dependencies the app never loads: OkHttp's GraalVM support, jsoup's RE2/J regexes.
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.**
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.jetbrains.annotations.**

# The Linux audio backend's native library resolves WebRTC classes and members by name.
-keep class dev.onvoid.webrtc.** { *; }
