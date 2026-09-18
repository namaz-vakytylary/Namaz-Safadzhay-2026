plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.namaz.safadzhay"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.namaz.safadzhay"
        minSdk = 23
        targetSdk = 35
        versionCode = 32
        versionName = "1.2"
    }

    signingConfigs {
        create("namazRelease") {
            storeFile = file("namaz-release.jks")
            storePassword = providers.environmentVariable("NAMAZ_STORE_PASSWORD").orNull
            keyAlias = "namaz"
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
