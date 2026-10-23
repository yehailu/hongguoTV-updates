import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)
// 包名后缀：-PpkgSuffix=xxx 或环境变量 HONGGUOTV_PKG_SUFFIX，留空则保持原包名
val pkgSuffix = (System.getenv("HONGGUOTV_PKG_SUFFIX") ?: (project.findProperty("pkgSuffix") as String?))
    ?.trim()?.takeIf { it.isNotEmpty() }
// 桌面/电视上的显示名，默认沿用原标题
val appLabel = (System.getenv("HONGGUOTV_APP_LABEL") ?: (project.findProperty("appLabel") as String?))
    ?.takeIf { it.isNotBlank() } ?: "红果TV"
// 最低系统：-PminSdk=24 或环境变量 HONGGUOTV_MIN_SDK，用于兼容老电视（默认 26）
val minSdkVersion = (System.getenv("HONGGUOTV_MIN_SDK") ?: (project.findProperty("minSdk") as String?))
    ?.toIntOrNull() ?: 27

android {
    namespace = "com.hongguotv.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = if (pkgSuffix == null) "com.hongguotv.nativeapp" else "com.hongguotv.nativeapp.$pkgSuffix"
        minSdk = minSdkVersion
        targetSdk = 36
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 28
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.24.0"
        resValue("string", "app_name", appLabel)
        // TV boxes stay landscape; phone builds pass -PscreenOrientation=unspecified.
        manifestPlaceholders["screenOrientation"] =
            (System.getenv("HONGGUOTV_SCREEN_ORIENTATION") ?: (project.findProperty("screenOrientation") as String?)) ?: "landscape"
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "decode"
    productFlavors {
        // Hardware decode only: no native FFmpeg payload, smallest APK.
        create("hw") { dimension = "decode" }
        // Software decode fallback for devices without a HEVC decoder.
        create("sw") { dimension = "decode" }
    }
    defaultConfig {
        // FFmpeg software decoders ship per ABI; phones are irrelevant, keep the two ARM targets.
        ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") }
    }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("delivery"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module") }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // FFmpeg software decoders (HEVC/H.264/VP9/AV1) only in the sw flavor.
    "swImplementation"("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0")
}
