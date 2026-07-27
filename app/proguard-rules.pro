# Keep BouncyCastle crypto providers/primitives used at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep secp256k1 JNI bindings.
-keep class fr.acinq.secp256k1.** { *; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.zenten.vault.**$$serializer { *; }
-keepclassmembers class com.zenten.vault.** {
    *** Companion;
}
-keepclasseswithmembers class com.zenten.vault.** {
    kotlinx.serialization.KSerializer serializer(...);
}
