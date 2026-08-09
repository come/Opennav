import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependency, on purpose. This module holds the arithmetic that decides
// whether the app tells you there is water under the keel, so it must be runnable and
// testable on a bare JVM: `./gradlew :core:depth:test --configure-on-demand`.
//
// The bytecode target is pinned to 17 to match what the Android modules consume, but the
// build deliberately does not request a Java 17 *toolchain*: that would force every
// contributor (and every CI box) to provision a second JDK before they can run the
// safety tests. Any JDK >= 17 works.

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
