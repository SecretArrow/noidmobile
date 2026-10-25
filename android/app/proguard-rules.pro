# Keep all classes in the JNI bridge package — JNI looks them up by name.
-keep class org.secretarrow.noidmobile.pow.** { *; }
-keep class org.secretarrow.noidmobile.data.model.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class org.secretarrow.noidmobile.**$$serializer { *; }
-keepclassmembers class org.secretarrow.noidmobile.** {
    *** Companion;
}
-keepclasseswithmembers class org.secretarrow.noidmobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}
