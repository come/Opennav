// Intentionally empty of `plugins { ... }` declarations.
//
// Every plugin is applied by the module that needs it, using the version catalog.
// Declaring the Android plugins here (even with `apply false`) would force Gradle to
// resolve the Android Gradle Plugin before it can configure anything at all, which
// makes `:core:depth` -- the module that holds the safety-critical logic -- impossible
// to build or test without a full Android SDK. Keeping the root build empty means:
//
//     ./gradlew :core:depth:test --configure-on-demand
//
// works on a bare JDK.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
