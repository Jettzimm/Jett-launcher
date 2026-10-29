import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jett.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jett.launcher"
        minSdk = 28
        targetSdk = 34
        versionCode = 29
        versionName = "3.8"
    }

    // Fixed signing key so every version installs over the last one. The key and its password
    // live in jett.keystore + keystore.properties, which are never uploaded anywhere public.
    val ks = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("jett") {
            storeFile = rootProject.file(ks.getProperty("storeFile", "jett.keystore"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias", "jett")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("jett") }
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("jett") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
