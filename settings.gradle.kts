rootProject.name = "HowManyPullups"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared:core")
include(":shared:data")
include(":composeApp")
