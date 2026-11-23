plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// JitPack passes the coordinates it serves (GROUP, and VERSION = the git tag); local builds use gradle.properties.
group = providers.environmentVariable("GROUP").orElse(providers.gradleProperty("tangentSdkGroup")).get()
version = providers.environmentVariable("VERSION").orElse(providers.gradleProperty("tangentSdkVersion")).get()

android {
    namespace = "com.tangent.sdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // `api`: the apps use these types directly (Mixpanel's instance, Play Billing's ProductDetails, Superwall's
    // register / PaywallPresentationHandler), so they come with the SDK instead of being declared again.
    api(libs.mixpanel.android)
    api(libs.adjust.android)
    api(libs.billing.ktx)
    api(libs.superwall.android)
    api(libs.kotlinx.coroutines.android)

    // Adjust reads the Play install referrer and the advertising ID through these.
    implementation(libs.installreferrer)
    implementation(libs.play.services.ads.identifier)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.material)

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "tangent-sdk"
            afterEvaluate { from(components["release"]) }
        }
    }
}
