plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Never check a private signing key into this public repository.
// CI injects it only for trusted main/explicit workflow_dispatch builds.
val stableStore = System.getenv("YEELIGHT_SIGNING_STORE")?.takeIf { it.isNotBlank() }
val stablePassword = System.getenv("YEELIGHT_SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val stableSigning = stableStore != null && stablePassword != null

android {
    signingConfigs {
        if (stableSigning) {
            create("stablePreview") {
                storeFile = file(stableStore!!)
                storeType = "PKCS12"
                storePassword = stablePassword!!
                keyAlias = "loft-dj-app"
                keyPassword = stablePassword
            }
        }
    }
    namespace = "io.github.lxsdd.yeelightloftdj"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lxsdd.yeelightloftdj"
        minSdk = 29
        targetSdk = 35
        versionCode = 16
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") {
            if (stableSigning) signingConfig = signingConfigs.getByName("stablePreview")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (stableSigning) signingConfig = signingConfigs.getByName("stablePreview")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Apache-2.0, physical drag-and-drop with edge autoscroll and drag handles.
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a mock in JVM unit tests.
    testImplementation("org.json:json:20240303")
}
