plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

val standalone = providers.environmentVariable("NEXUS_GAME").orNull == "1"
val packageId = providers.environmentVariable("NEXUS_PACKAGE").orElse("com.nexusengine.app")
val appLabel = providers.environmentVariable("NEXUS_NAME").orElse("Nexus Engine")
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
        applicationId = packageId.get()
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["nexusIcon"] = providers.environmentVariable("NEXUS_ICON").orElse("@drawable/ic_nexus").get()
        manifestPlaceholders["nexusLabel"] = appLabel.get()
        manifestPlaceholders["nexusOrientation"] = providers.environmentVariable("NEXUS_ORIENTATION").orElse("unspecified").get()
        if (!standalone) manifestPlaceholders["nexusEditor"] = "true"
        else manifestPlaceholders["nexusEditor"] = "false"
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
}

flutter { source = "../.." }
