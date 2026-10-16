# liblitertlm_jni.so reads LiteRT-LM's Kotlin config objects (SamplerConfig,
# ThinkingConfig, ...) by looking up their getters by name over JNI. R8 sees
# no Java caller for those getters and renames or strips them, and the app
# aborts in nativeCreateConversation with "JNI DETECTED ERROR: mid == null".

-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
