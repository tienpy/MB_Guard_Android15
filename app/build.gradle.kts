plugins {
    id("com.android.application")
}

android {
    namespace = "com.mrtien.mbbankguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mrtien.mbbankguard15"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.5.1"
    }

    signingConfigs {
        create("mbGuard15") {
            storeFile = rootProject.file("signing/mbguard15.p12")
            storePassword = "MBGuard15_2026"
            keyAlias = "mbguard15"
            keyPassword = "MBGuard15_2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("mbGuard15")
        }
        release {
            signingConfig = signingConfigs.getByName("mbGuard15")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.84")
}
