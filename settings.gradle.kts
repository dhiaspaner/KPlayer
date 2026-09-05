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

rootProject.name = "kplayer"
include(":audio")
include(":core")
include(":video")
include(":sample")
include(":session")
include(":state-machine")
include(":ui")
