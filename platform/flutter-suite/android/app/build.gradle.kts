plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("dev.flutter.flutter-gradle-plugin") }
android {
 namespace="dev.sakura.suite"
 compileSdk=36
 ndkVersion="27.2.12479018"
 defaultConfig { applicationId="dev.sakura.suite";minSdk=26;targetSdk=34;versionCode=200;versionName="0.2.0" }
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions {jvmTarget="17"}
 flavorDimensions += "application"
 productFlavors {
  mapOf("settings" to "Settings","files" to "Files","phone" to "Phone","messages" to "Messages","contacts" to "Contacts","gallery" to "Gallery","camera" to "Camera","clock" to "Clock","calculator" to "Calculator","calendar" to "Calendar","music" to "Music","recorder" to "Recorder","notes" to "Notes","installer" to "Installer","wallpapers" to "Wallpapers","updater" to "Updater","terminal" to "Terminal","coremanager" to "Core Manager","patch" to "Patch").forEach { (kind,label) ->
   create(kind) { dimension="application";applicationId="dev.sakura.$kind";manifestPlaceholders["appLabel"]="Sakura $label";manifestPlaceholders["sakuraApp"]=kind }
  }
 }
 buildTypes { getByName("release") {isMinifyEnabled=false;signingConfig=signingConfigs.getByName("debug")} }
}
flutter {source="../.."}
dependencies {implementation("androidx.core:core-ktx:1.15.0")}
