plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing. The point of the checked-in key is that an APK built today installs *over*
// one built last week: Android refuses an upgrade signed by a different key, and a
// keystore generated fresh on each CI run is a different key every time.
//
// `ci/opennav-ci.keystore` is a throwaway with a published password, committed on
// purpose. It is not a release key and must never become one -- see ci/README.md. A real
// release key is supplied out of band through the OPENNAV_KEYSTORE_* environment
// variables and takes precedence whenever it is present.
val ciKeystore = rootProject.file("ci/opennav-ci.keystore")
val ciKeystorePassword = "opennav-ci"
val releaseKeystorePath: String? = System.getenv("OPENNAV_KEYSTORE_PATH")

android {
    namespace = "org.opennav"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.opennav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("shared") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("OPENNAV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENNAV_KEY_ALIAS") ?: "opennav"
                keyPassword = System.getenv("OPENNAV_KEY_PASSWORD")
                    ?: System.getenv("OPENNAV_KEYSTORE_PASSWORD")
            } else if (ciKeystore.exists()) {
                storeFile = ciKeystore
                storePassword = ciKeystorePassword
                keyAlias = "opennav"
                keyPassword = ciKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            // Debug and release share the key, so an install of either can replace the
            // other without uninstalling first.
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core:depth"))
    implementation(project(":core:geo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android.sdk)

    testImplementation(libs.junit)
}

tasks.register("printSigningReport") {
    val source = releaseKeystorePath ?: ciKeystore.path
    doLast { println("APKs will be signed with: $source") }
}
