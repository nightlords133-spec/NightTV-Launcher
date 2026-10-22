plugins {
    id("com.android.application")
}

// Номер сборки GitHub Actions = номер версии (build-N в Releases)
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ru.nightlord.tvlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nightlord.tvlauncher"
        minSdk = 22
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Постоянный ключ: без него каждая сборка подписана разным ключом
    // и обновление поверх старой версии невозможно.
    signingConfigs {
        create("night") {
            storeFile = rootProject.file("signing/nighttv.jks")
            storePassword = "nighttv-key"
            keyAlias = "nighttv"
            keyPassword = "nighttv-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("night")
        }
        debug {
            signingConfig = signingConfigs.getByName("night")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
