# Sherpa-onnx Kotlin bindings call into `libsherpa-onnx-jni.so` by class +
# method name; R8 obfuscation would break the JNI symbol lookup at runtime.
# `native <methods>` alone isn't enough because sherpa-onnx also looks up
# field signatures (e.g. the long `ptr` handles in OnlineRecognizer/OnlineStream).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
    long ptr;
}
