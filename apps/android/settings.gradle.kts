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

rootProject.name = "dono-da-vez"

include(":app")
include(":core", ":auth", ":map", ":property", ":wallet", ":portfolio", ":proposals", ":profile")
