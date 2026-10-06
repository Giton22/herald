# kotlinx.serialization keeps generated serializers via its bundled consumer rules.
# Ktor / OkHttp optional dependencies
-dontwarn org.slf4j.**

# Comments on a selection read every text in the selection container by this name (CommentSelection.android.kt).
-keepclassmembers class androidx.compose.foundation.text.selection.SelectionState {
    public java.util.List getSelectableTexts();
}

# WebRTC (GPT-Live voice calls) is reached from native code by name, and its AAR ships no rules of its own.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
