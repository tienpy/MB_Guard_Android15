plugins {
    id("com.android.application")
}

android {
    namespace = "com.mrtien.autoskip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mrtien.autoskip"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.2.4"
    }

    signingConfigs {
        create("releaseKey") {
            storeFile = rootProject.file("signing/mbguard15.p12")
            storePassword = "MBGuard15_2026"
            keyAlias = "mbguard15"
            keyPassword = "MBGuard15_2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseKey")
        }
        release {
            signingConfig = signingConfigs.getByName("releaseKey")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
