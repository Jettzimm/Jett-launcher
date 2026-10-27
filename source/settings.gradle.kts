pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral(); maven("https://maven-central.storage-download.googleapis.com/maven2/") }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://maven-central.storage-download.googleapis.com/maven2/") }
}
rootProject.name = "JettLauncher"
include(":app")
