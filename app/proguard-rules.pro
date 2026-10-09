# ---------------------------------------------------------------- kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.flexy.f1live.**$$serializer { *; }
-keepclassmembers class com.flexy.f1live.** {
    *** Companion;
}
-keepclasseswithmembers class com.flexy.f1live.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------- OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
-keepclassmembers class okhttp3.internal.platform.** { *; }

# ---------------------------------------------------------------- Coil 3
-dontwarn coil3.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# ---------------------------------------------------------------- Compose / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keep class com.flexy.f1live.F1App { *; }
