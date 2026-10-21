plugins {
    id("com.android.application")
}

android {
    namespace = "ru.nightlord.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nightlord.tvlauncher"
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
