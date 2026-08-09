import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM, like every `:core:*` module: distances and bearings decide what the app
// tells you about a leg, so they are unit-testable without a device.
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
