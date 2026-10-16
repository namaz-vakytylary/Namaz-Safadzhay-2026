import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.namaz.safadzhay"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.namaz.safadzhay.test"
        minSdk = 23
        targetSdk = 35
        versionCode = 37
        versionName = "1.4-test1"
    }

    signingConfigs {
        create("namazRelease") {
            storeFile = providers.environmentVariable("NAMAZ_KEYSTORE_PATH").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("NAMAZ_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("NAMAZ_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("NAMAZ_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("namazRelease")
        }
    }

    // A signed TEST APK is still a release build, but needs the same UI preview
    // tools as debug. Production application IDs retain src/release/java,
    // whose RamadanUiPreview has no artificial date or clock override.
    sourceSets {
        if (defaultConfig.applicationId == "ru.namaz.safadzhay.test") {
            getByName("release").java.setSrcDirs(listOf("src/debug/java"))
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            // Forward optional build-host proxy settings to Robolectric's SDK downloader.
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { key ->
                System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
            }
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("ru.rustore.sdk:appupdate:10.5.1")
    // Prepared verifier only: no signed feed or production trust key is activated.
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}

// Unit tests and lint need no signing secrets. A release APK must never fall back
// to the debug key or an unsigned build when its protected inputs are missing.
val verifyReleaseSigning by tasks.registering {
    doLast {
        val required = listOf("NAMAZ_KEYSTORE_PATH", "NAMAZ_STORE_PASSWORD", "NAMAZ_KEY_ALIAS", "NAMAZ_KEY_PASSWORD")
        val missing = required.filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing is not configured; missing: " + missing.joinToString())
        }
        val keystore = KeyStore.getInstance(
            file(providers.environmentVariable("NAMAZ_KEYSTORE_PATH").get()),
            providers.environmentVariable("NAMAZ_STORE_PASSWORD").get().toCharArray())
        val certificate = keystore.getCertificate(providers.environmentVariable("NAMAZ_KEY_ALIAS").get())
            ?: throw GradleException("Signing certificate is missing")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val expected = rootProject.file("verification/release-certificate.sha256").readText().trim()
        check(fingerprint == expected) { "Unexpected TEST signing certificate" }
        check(android.defaultConfig.applicationId == "ru.namaz.safadzhay.test") { "This branch builds TEST only" }

    }
}
// AGP omits validateSigningRelease entirely if the signing inputs are absent.
// Guard packaging as well so assembleRelease cannot silently create an unsigned APK.
tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease") }.configureEach {
    dependsOn(verifyReleaseSigning)
}

// Calling assembleRelease locally must enforce the same checks as CI.
val verifyReleaseQuality by tasks.registering {
    dependsOn("testDebugUnitTest", "testReleaseUnitTest", "lintDebug", "lintRelease")
}
tasks.matching { it.name == "packageRelease" }.configureEach { dependsOn(verifyReleaseQuality) }
