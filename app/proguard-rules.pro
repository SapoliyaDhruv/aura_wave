# NewPipeExtractor runs YouTube's player JavaScript through Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**

# NewPipeExtractor finds parts of itself and its JSON/HTML parsers by name; keep them whole.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**

# Backup files and the saved queue are read with kotlinx.serialization.
-keepclassmembers @kotlinx.serialization.Serializable class com.aurawave.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Rhino's optional desktop-Java linker; not used on Android.
-dontwarn jdk.dynalink.**
