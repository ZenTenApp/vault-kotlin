# Keep BouncyCastle crypto providers/primitives used at runtime.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep secp256k1 JNI bindings.
-keep class fr.acinq.secp256k1.** { *; }

# Release-only: R8 sees the whole sshd-common dependency, but we use exactly one class
# from it (BCrypt, for bcrypt_pbkdf). These references live in sshd paths we never call:
# JMX exception classes and the net.i2p EdDSA provider. Suppressing them is safe because
# the classes that reference them are never loaded at run time.
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
-dontwarn net.i2p.crypto.eddsa.EdDSAPrivateKey
-dontwarn net.i2p.crypto.eddsa.EdDSAPublicKey

# slf4j binds a logging backend by name. No backend is bundled, so the binding itself
# warns; slf4j falls back to a no-op logger. Logger calls in sshd code we never reach.
-dontwarn org.slf4j.impl.StaticLoggerBinder

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
