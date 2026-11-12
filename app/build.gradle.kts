import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing material is read from an untracked `keystore.properties` at the
// repository root. Keeping it out of this (committed) script means passwords and
// key paths never enter version control. When the file is missing — a fresh clone,
// or a contributor without signing rights — the release variant still builds, but
// produces the unsigned artifact instead of failing configuration.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
// All four values are required; a partially filled file (e.g. the template with
// blank passwords) is treated as "no signing configured" rather than causing a
// confusing signing failure at packaging time.
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigning = signingKeys.all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "app.zenten.vault"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.zenten.vault"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Declared only when keystore.properties exists, so `assembleRelease` never
    // attempts to sign with absent credentials.
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without signing material the release variant stays unsigned (see above).
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle + jspecify both ship this OSGi metadata file.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                // Quartz ships JVM 21 bytecode; run host-side unit tests on a JDK 21
                // runtime (auto-provisioned) even though the app itself targets JVM 17.
                it.javaLauncher.set(
                    javaToolchains.launcherFor {
                        languageVersion.set(JavaLanguageVersion.of(21))
                    },
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    // BouncyCastle: SSH-side crypto only (OpenSSH v1 parse, bcrypt_pbkdf, AES-CTR,
    // Ed25519) — the non-standard half no library exposes the raw seed for.
    implementation(libs.bouncycastle.prov)
    // Apache MINA provides the OpenSSH-compatible bcrypt_pbkdf primitive. We retain
    // ownership of the container/decrypt parser so raw Ed25519 seed extraction is explicit.
    implementation(libs.sshd.common)
    // Quartz Nostr KMP library: owns the standard Nostr half — NIP-06 (key
    // derivation), NIP-19 (bech32 npub/nsec), NIP-44 v2 (encryption), events.
    // Battle-tested (powers Amethyst); pulls secp256k1 JNI transitively.
    // Parity proven byte-for-byte in QuartzParityTest.
    implementation(libs.quartz)
    // Pin the secp256k1 android JNI ABI explicitly (also a Quartz transitive).
    implementation(libs.secp256k1.kmp.jni.android)
    // zxcvbn estimates how many guesses a VaultKey passphrase would cost an attacker.
    // The passphrase is the only secret protecting a VaultKey backup that may already be
    // published to public relays, so strength is measured rather than pattern-matched.
    implementation(libs.zxcvbn)
    // Unit tests
    // secp256k1 (Schnorr, NIP-44 ECDH) needs the JVM JNI artifact to run in host-side
    // (Robolectric/JUnit) tests; the -jni-android artifact only loads on a device/emulator.
    testImplementation(libs.secp256k1.kmp.jni.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    // Robolectric tests use the JUnit4 runner; the Vintage engine runs them on the
    // JUnit Platform configured for these unit tests.
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
