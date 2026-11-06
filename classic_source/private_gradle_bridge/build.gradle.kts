import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") version "2.4.21" }

// Reuses the updated compiler generation from Loft DJ (staged, pending real Gradle CI).
// No proprietary OEM DEX/APK is ever consumed or embedded by this project.
val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("ANDROID_HOME is required (same SDK provided by android-actions/setup-android@v4)")
val jar36 = file("$sdk/platforms/android-36/android.jar")
val jar35 = file("$sdk/platforms/android-35/android.jar")
val androidApi = if (jar36.isFile) jar36 else jar35
require(androidApi.isFile) { "Android SDK platform 36/35 not installed" }

dependencies { compileOnly(files(androidApi)) }

sourceSets.main {
    java.srcDirs("../bridge/src")
}
kotlin.sourceSets.named("main") {
    kotlin.srcDirs("../checkpoint/src", "../bridge/kotlin")
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
tasks.jar { archiveBaseName.set("classic-bridge") }

tasks.register<Copy>("stageBridgeInputs") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath) { include("kotlin-stdlib-*.jar") }
    into(layout.buildDirectory.dir("dex-input"))
}
