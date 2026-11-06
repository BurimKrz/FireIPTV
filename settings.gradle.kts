pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FireIPTV"

include(":app")
include(":core:data", ":core:ffmpeg", ":core:parser", ":core:player", ":core:ui")
include(":feature:browse", ":feature:player", ":feature:settings")
