plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
}
android {
    namespace = "dev.sakura.home"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.sakura.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 103
        versionName = "0.1.3"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
 buildTypes { getByName("release") { isMinifyEnabled = false; isShrinkResources = false; signingConfig = signingConfigs.getByName("debug") } }
}
flutter { source = "../.." }
dependencies { implementation(project(":liquidglass")) }
