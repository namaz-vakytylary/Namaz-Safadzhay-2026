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
        versionCode = 2
        versionName = "1.1"
    }
}

kotlin {
    jvmToolchain(17)
}
