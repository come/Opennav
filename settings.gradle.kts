pluginManagement {
    repositories {
        // Scoped so that Google's repository is only ever queried for artifacts it
        // actually owns. Besides being the recommended supply-chain hygiene, this is
        // what lets the pure-JVM `:core:*` modules build in environments that cannot
        // reach dl.google.com.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.google")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "opennav"

// Phase 0 only. The remaining modules of the target architecture
// (:core:geo, :core:tide, :data:charts, :data:persistence, :feature:*)
// are deliberately not created yet -- see docs/PHASE0.md.
include(":core:depth")
include(":core:geo")
include(":app")
