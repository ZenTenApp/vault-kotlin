# Keep BouncyCastle crypto providers/primitives used at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep secp256k1 JNI bindings.
-keep class fr.acinq.secp256k1.** { *; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.zenten.vault.**$$serializer { *; }
-keepclassmembers class app.zenten.vault.** {
    *** Companion;
}
-keepclasseswithmembers class app.zenten.vault.** {
    kotlinx.serialization.KSerializer serializer(...);
}
