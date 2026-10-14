# kotlinx.serialization keeps generated serializers via its bundled consumer rules; the Wear libraries ship their own.

# WebRTC (live voice calls) is reached from native code by name, and its AAR ships no rules of its own.
# Native code calls only what it marks @CalledByNative (the default rules keep native methods' names), so keep
# those members, their classes and the types in their signatures; R8 may shrink and rename the rest.
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNative <init>(...);
}
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNative <methods>;
}
# Nothing in 150.7871.01 uses the unchecked form; it's here for a WebRTC update that does.
-keepclasseswithmembers,includedescriptorclasses class org.webrtc.** {
    @org.webrtc.CalledByNativeUnchecked <methods>;
}
-dontwarn org.webrtc.**
# Its JNI glue too: JNI_OnLoad finds these by name, and renamed they abort the app as a call starts.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**
