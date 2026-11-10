import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fully PUBLIC source-only regression project. Uses the already selected Kotlin
// generation from the public Loft DJ build, never downloads or reads an OEM APK.
// Test Android stubs and the exact production DJ runtime sources are compiled
// together; a passing Python source check alone is insufficient.
plugins {
    kotlin("jvm") version "2.2.20"
}
sourceSets.named("main") {
    java.srcDirs("../bridge/src", "src/main/java")
    java.include(
        "android/content/Context.java",
        "io/github/lxsdd/yeelightclassicdj/runtime/DjRuntime.java"
    )
}
kotlin {
    sourceSets.named("main") {
        kotlin.srcDirs(
            "../checkpoint/src", "../checkpoint/tests",
            "../checkpoint/tests/android-stubs", "../bridge/kotlin"
        )
    }
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }

val testMains = linkedMapOf(
    "Coordinator" to "loftdj.classic.CoordinatorTestKt",
    "CancelDuringStart" to "loftdj.classic.CancelDuringStartTestKt",
    "BeatAnalyzer" to "loftdj.classic.BeatAnalyzerTestKt",
    "DjEngine" to "loftdj.classic.DjEngineTestKt",
    "PcmBands" to "loftdj.classic.PcmBandsTestKt",
    "CaptureSession" to "loftdj.classic.CaptureSessionTestKt",
    "AndroidAudioRecordInput" to "loftdj.classic.AndroidAudioRecordInputTestKt",
    "YeelightReverseMusicTransport" to "loftdj.classic.YeelightReverseMusicTransportTestKt",
    "CheckpointRuntime" to "io.github.lxsdd.yeelightclassicdj.runtime.CheckpointRuntimeTestKt"
)
val runTasks = testMains.map { (label, className) ->
    tasks.register<JavaExec>("run" + label) {
        group = "verification"
        description = "Run source-bound Classic offline JVM regression: " + label
        dependsOn(tasks.named("classes"))
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(className)
        maxHeapSize = "512m"
    }
}
tasks.register("verifyClassicJvm") {
    group = "verification"
    description = "Compile ALL Classic JVM/DJ source and execute all nine real integration test programs."
    dependsOn(runTasks)
}
