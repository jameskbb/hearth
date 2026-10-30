import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties at the repository root (gitignored) names the
// keystore and its passwords; see docs/install.md. Without it, release builds fall
// back to this machine's debug key, with a warning.
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val releaseKeys: Properties? = keystorePropertiesFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
// CI selects one Android runtime per job. Robolectric 4.14 retains API 21/22;
// 4.16 covers API 23 through 36. The normal unit suite stays fast and SDK-free.
val compatibilitySdks = providers.gradleProperty("compatSdks").orNull?.split(",")?.map { it.toInt() }?.also { sdks ->
    require(sdks.isNotEmpty() && sdks.all { it in 21..36 }) { "compatSdks must contain API levels 21 through 36" }
    require(sdks.all { it < 23 } || sdks.all { it >= 23 }) { "Run API 21/22 separately from API 23+" }
}
fun releaseKey(name: String): String =
    releaseKeys?.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("keystore.properties: '$name' is missing or empty (see docs/install.md)")

android {
    namespace = "io.github.jameskbb.hearth"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jameskbb.hearth"
        minSdk = 21 // Android TV 5.0 and later, including Fire OS 5 (API 22)
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2"
    }

    signingConfigs {
        if (releaseKeys != null) {
            create("release") {
                // A relative storeFile is resolved against the repository root.
                val store = rootProject.file(releaseKey("storeFile"))
                if (!store.isFile) {
                    throw GradleException("keystore.properties: storeFile not found: ${releaseKey("storeFile")}")
                }
                storeFile = store
                storePassword = releaseKey("storePassword")
                keyAlias = releaseKey("keyAlias")
                keyPassword = releaseKey("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeys != null) "release" else "debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            if (compatibilitySdks != null) {
                it.include("**/compat/**")
                it.systemProperty("robolectric.enabledSdks", compatibilitySdks.joinToString(","))
                it.systemProperty("hearth.compatSdks", compatibilitySdks.joinToString(","))
                val evidence = layout.buildDirectory.dir("compatibility-runtimes").get().asFile
                it.systemProperty("hearth.compatEvidence", evidence.absolutePath)
                it.outputs.dir(evidence)
                it.doFirst { evidence.deleteRecursively() }
            } else {
                it.exclude("**/compat/**")
            }
        }
    }
    lint {
        // False positive: both produceState calls (AppTile, Backdrop) assign `value` in the
        // producer lambda, yet the Compose runtime's lint check reports both as not assigning it.
        // Versions are pinned on purpose (gradle/libs.versions.toml), so "newer version" notes
        // are noise; upgrades are deliberate changes.
        disable += setOf("ProduceStateDoesNotAssignValue", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

// Say so whenever a release APK is about to be signed with the per-machine debug key.
if (releaseKeys == null) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") }) {
            logger.warn(
                "WARNING: keystore.properties not found; the release APK is signed with this machine's " +
                    "debug key, and an APK signed with a different key cannot update it. See docs/install.md.",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // Framework tests must use the selected Android SDK's org.json, not the JVM substitute.
    if (compatibilitySdks == null) testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(if (compatibilitySdks?.all { it < 23 } == true) libs.robolectric.legacy else libs.robolectric.modern)
}
