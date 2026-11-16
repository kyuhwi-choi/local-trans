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
rootProject.name = "local-trans"
include(":app")

// 번역 벤치마크 전용 앱 (bench/README.md)
include(":benchmlkit")
project(":benchmlkit").projectDir = file("bench/mlkit")
