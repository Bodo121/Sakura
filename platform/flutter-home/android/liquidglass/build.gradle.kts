plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.liquidglass"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("com.google.android.material:material:1.12.0")
    compileOnly("androidx.viewpager2:viewpager2:1.1.0")
}
