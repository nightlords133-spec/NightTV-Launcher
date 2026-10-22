plugins {
    id("com.android.application")
}

// Ключ YouTube Data API: секрет YT_API_KEY в настройках репозитория (необязательно)
val ytApiKey = (System.getenv("YT_API_KEY") ?: "").trim()

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
        buildConfigField("String", "YT_API_KEY", "\"$ytApiKey\"")
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

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
