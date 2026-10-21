import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Platform-independent domain logic: nutrition engine, AI provider contracts,
// Sign in with ChatGPT protocol logic, parsers, recipe engine and ranking.
// Nothing in commonMain may depend on Android or the JVM so it can be compiled
// for iOS later (see IOS_ROADMAP.md). The JVM target is consumed by :app and runs
// the test suite.
//
// iOS targets are built only on macOS (the iOS CI job, or a Mac with Xcode), or with
// -Pnouri.ios=true, so Linux and Android builds never need the Kotlin/Native toolchain.
val buildIos = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("nouri.ios").orNull == "true"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    if (buildIos) {
        // A static framework, bundled for device and simulator: ./gradlew :shared:assembleNouriSharedXCFramework
        val xcf = XCFramework("NouriShared")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "NouriShared"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
        }
        if (buildIos) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    systemProperty("nouri.repoRoot", rootDir.absolutePath)
    // RecipeLibraryTest reads the library sources, so editing them must re-run the tests.
    inputs.dir(rootProject.file("data/recipes")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("recipeLibrary")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
