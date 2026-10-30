// Tiny installable apps that exercise Hearth's app discovery and artwork
// fallbacks, one product flavor per case. See docs/testing.md.
//   ./gradlew :fixtures:assembleDebug   # fixtures/build/outputs/apk/<flavor>/debug/
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.jameskbb.hearth.fixture"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "kind"
    productFlavors {
        // TV app with a 16:9 banner (LEANBACK_LAUNCHER).
        create("banner") {
            dimension = "kind"
            applicationId = "io.github.jameskbb.hearth.fixture.banner"
            resValue("string", "fixture_label", "Fixture banner")
        }
        // TV app with an icon but no banner.
        create("icononly") {
            dimension = "kind"
            applicationId = "io.github.jameskbb.hearth.fixture.icononly"
            resValue("string", "fixture_label", "Fixture icon only")
        }
        // Phone app: LAUNCHER only, not built for TV.
        create("phone") {
            dimension = "kind"
            applicationId = "io.github.jameskbb.hearth.fixture.phone"
            resValue("string", "fixture_label", "Fixture phone")
        }
        // TV app with no banner and a fully transparent icon, to exercise the
        // monogram fallback (Android requires an icon, so the art is empty).
        create("noart") {
            dimension = "kind"
            applicationId = "io.github.jameskbb.hearth.fixture.noart"
            resValue("string", "fixture_label", "Fixture no art")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
