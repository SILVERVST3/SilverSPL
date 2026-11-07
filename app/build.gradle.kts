plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.silver.spl"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.silver.spl"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Chave fixa no repositório: cada APK novo instala por cima do anterior sem desinstalar.
    signingConfigs {
        create("silver") {
            storeFile = file("silverspl.jks")
            storePassword = "silverspl"
            keyAlias = "silverspl"
            keyPassword = "silverspl"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("silver")
        }
        debug {
            signingConfig = signingConfigs.getByName("silver")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
