plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

val keyStore = providers.environmentVariable("NEXUS_KEYSTORE").orNull

android {
    namespace = "com.nexusengine.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.nexusengine.app"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }
    if (keyStore != null) {
        signingConfigs.create("nexusRelease") {
            storeFile = file(keyStore)
            storePassword = providers.environmentVariable("NEXUS_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("NEXUS_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("NEXUS_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (keyStore == null) "debug" else "nexusRelease")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.android.tools.build:apksig:8.7.3")
}

flutter { source = "../.." }
