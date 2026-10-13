pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.google\\.testing.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://nexus-external.rustore.ru/repository/maven-rustore-exposed")
            content { includeGroup("ru.rustore.sdk") }
        }
    }
}
rootProject.name = "NamazVakytylaryTest"
include(":app")
