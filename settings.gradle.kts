pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // LiveKit audioswitch 의존성
    }
}

rootProject.name = "HybridWebMessenger"
include(":app")
//include(":resource")
//include(":base")
//include(":service")
//project(":service").projectDir = file("../sCallingCore/service")
//project(":resource").projectDir = file("../sCallingCore/resource")
//project(":base").projectDir = file("../sCallingCore/base")
