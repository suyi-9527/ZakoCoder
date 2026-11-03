plugins {
    alias(libs.plugins.android.application)
}

val compatibilityAbis = providers.gradleProperty("compatibilityAbis").orElse("arm64-v8a").get()
    .split(",").map(String::trim).distinct()

android {
    namespace = "dev.zakocoder.app.kernelprobe"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.zakocoder.app.kernelprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-probe"
        ndk { abiFilters += compatibilityAbis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { aidl = true }
    sourceSets.getByName("main").jniLibs.directories.add(
        rootProject.file("runtime-artifacts/jniLibs").absolutePath,
    )
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libzakocoder_*.so" }
    }
}
