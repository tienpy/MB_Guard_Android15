plugins {
    id("com.android.application")
}
android {
    namespace = "com.mrtien.tienshakeactions"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mrtien.tienshakeactions"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
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
        release {
            signingConfig = signingConfigs.getByName("releaseKey")
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("releaseKey")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
