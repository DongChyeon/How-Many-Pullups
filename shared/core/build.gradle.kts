plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    // jvm(): desktop target so the D2 logic is unit-testable and the eval CLI runs without a device.
    jvm()

    android {
        namespace = "pullup.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Replay landmark dumps through the D2 detector and print count vs ground truth.
// ./gradlew :shared:core:evalLandmarks -Pdir=docs/private/dataset/landmarks [-Pmeta=docs/private/dataset/meta] [-Ptrace=<file substring>]
tasks.register<JavaExec>("evalLandmarks") {
    group = "verification"
    description = "Evaluate RepDetector on landmark JSON files (-Pdir=<landmarks dir>, optional -Pmeta=<meta dir>)."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("pullup.core.eval.EvalLandmarksKt")
    workingDir = rootDir
    val dir = providers.gradleProperty("dir").orElse("docs/private/dataset/landmarks")
    val meta = providers.gradleProperty("meta")
    argumentProviders += CommandLineArgumentProvider { listOfNotNull(dir.get(), meta.orNull) }
    providers.gradleProperty("trace").orNull?.let { systemProperty("eval.trace", it) }
}
