// All Gradle plugins are loaded once, on the root classpath, so the Android Gradle
// Plugin and the Kotlin Gradle Plugin share a class loader. The Android plugin is
// only added when Android modules are part of the build (see settings.gradle.kts).
buildscript {
    val skipAndroid = System.getProperty("nouri.skipAndroid")?.toBoolean() == true ||
        System.getenv("NOURI_SKIP_ANDROID") == "1"
    repositories {
        if (!skipAndroid) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        if (!skipAndroid) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.kotlin.compose.compiler.plugin)
            classpath(libs.ksp.gradle.plugin)
        }
    }
}
