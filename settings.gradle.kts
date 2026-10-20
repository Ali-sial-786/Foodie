pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Android modules need the Android SDK and Google Maven. Environments without them
// (for example a restricted CI sandbox) can still build and test :shared with
// -Dnouri.skipAndroid=true or NOURI_SKIP_ANDROID=1.
val skipAndroid = System.getProperty("nouri.skipAndroid")?.toBoolean() == true ||
    System.getenv("NOURI_SKIP_ANDROID") == "1"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!skipAndroid) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.android.*")
                    includeGroupByRegex("com\\.google\\.mlkit.*")
                    includeGroupByRegex("com\\.google\\.firebase.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Nouri"

include(":shared")
if (!skipAndroid) {
    include(":app")
}
