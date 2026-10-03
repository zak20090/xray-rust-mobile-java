plugins {
    id("com.android.library")
}

val xrayMobileCoordinates = listOf(
    providers.gradleProperty("XRAY_RUST_MOBILE_GROUP").get(),
    providers.gradleProperty("XRAY_RUST_MOBILE_ARTIFACT").get(),
    providers.gradleProperty("XRAY_RUST_MOBILE_VERSION").get(),
).joinToString(":")

android {
    namespace = "org.xrayrust.mobile.compat"
    compileSdk = 35

    defaultConfig {
        // Same minSdk as the upstream xraymobile module.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Log.* calls in CoreController must not throw in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Exposed as `api`: XrayCoreException and the snapshot types appear in this library's signatures.
    api(xrayMobileCoordinates)

    testImplementation("junit:junit:4.13.2")
    // android.jar stubs in unit tests do not implement org.json.
    testImplementation("org.json:json:20240303")
}
