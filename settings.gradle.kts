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
rootProject.name = "hearth"
include(":app")
include(":fixtures")  // test apps for discovery and artwork fallbacks
include(":demoapps")  // unaffiliated local service stand-ins for README captures
