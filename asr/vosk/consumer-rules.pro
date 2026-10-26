# Vosk reaches libvosk.so through JNA, which binds methods/structs reflectively.
# Without these keeps, R8 (full mode in particular) strips the classes JNA needs
# and the recognizer fails at runtime with UnsatisfiedLink / NPE.

-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }

# JNA references desktop AWT types that don't exist on Android; silence them.
-dontwarn java.awt.**
