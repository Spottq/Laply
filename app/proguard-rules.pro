# ---------------------------------------------------------------- kotlinx.serialization
# The plugin generates a $$serializer object per @Serializable class and a `serializer()` factory
# on the companion; both are only reached reflectively, so R8 must not strip or rename them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.flexy.f1live.**$$serializer { *; }
-keepclassmembers class com.flexy.f1live.** {
    *** Companion;
}
-keepclasseswithmembers class com.flexy.f1live.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the runtime's own serializer lookup intact.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------- OkHttp / Okio
# OkHttp references Conscrypt/BouncyCastle/OpenJSSE providers and Animal Sniffer annotations that
# are not on Android; they are optional at runtime.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
-keepclassmembers class okhttp3.internal.platform.** { *; }

# ---------------------------------------------------------------- Coil 3
# Coil discovers fetchers/decoders through ServiceLoader-free registration, but its okhttp network
# layer touches optional Ktor/slf4j symbols that are not packaged here.
-dontwarn coil3.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# ---------------------------------------------------------------- Compose / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep the Application subclass: it is named in the manifest and instantiated by the framework.
-keep class com.flexy.f1live.F1App { *; }
