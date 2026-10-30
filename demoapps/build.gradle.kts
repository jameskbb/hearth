// Unaffiliated local stand-ins used only for README screenshots and video.
// Service names identify examples, not official clients. Banners are rendered from
// design/demo-apps/ by scripts/render-brand.sh.
//   ./gradlew :demoapps:assembleDebug   # demoapps/build/outputs/apk/<flavor>/debug/
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.jameskbb.hearth.demo"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    flavorDimensions += "app"
    productFlavors {
        create("marquee") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.marquee"
            resValue("string", "demo_label", "Hulu")
        }
        create("lowtide") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.lowtide"
            resValue("string", "demo_label", "Disney+")
        }
        create("pinhole") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.pinhole"
            resValue("string", "demo_label", "Prime Video")
        }
        create("northwind") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.northwind"
            resValue("string", "demo_label", "ESPN")
        }
        create("puddlejump") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.puddlejump"
            resValue("string", "demo_label", "YouTube")
        }
        create("kestrel") {
            dimension = "app"
            applicationId = "io.github.jameskbb.hearth.demo.kestrel"
            resValue("string", "demo_label", "Netflix")
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
