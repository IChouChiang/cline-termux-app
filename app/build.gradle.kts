plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Parcelable form state (schedule editor, pending restore request) — docs/07 §12.
    id("org.jetbrains.kotlin.plugin.parcelize")
}

// Release signing (docs/release-signing.md). The key stays outside the repo and is named by four
// Gradle properties: clineTermuxReleaseStoreFile, clineTermuxReleaseStorePassword,
// clineTermuxReleaseKeyAlias and clineTermuxReleaseKeyPassword. The release build uses the key only
// when all four are set and the store file exists. With none of them set it is signed with the
// debug key, so any checkout still builds an installable release APK, and a build that packages a
// release APK or bundle warns why. With some set but the set unusable, such a build fails instead:
// a release APK signed with the debug key can neither update a release-key install nor be updated
// by one. Other builds are not affected (configuration runs for debug builds and tests too).
fun releaseSigningProperty(name: String) = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
val releaseStorePath = releaseSigningProperty("clineTermuxReleaseStoreFile")
val releaseStoreFile = releaseStorePath?.let { file(it) }?.takeIf { it.isFile }
val releaseStorePassword = releaseSigningProperty("clineTermuxReleaseStorePassword")
val releaseKeyAlias = releaseSigningProperty("clineTermuxReleaseKeyAlias")
val releaseKeyPassword = releaseSigningProperty("clineTermuxReleaseKeyPassword")
val releaseSigningProperties = listOf(
    "clineTermuxReleaseStoreFile" to releaseStorePath,
    "clineTermuxReleaseStorePassword" to releaseStorePassword,
    "clineTermuxReleaseKeyAlias" to releaseKeyAlias,
    "clineTermuxReleaseKeyPassword" to releaseKeyPassword,
)
val releaseSigningUnset = releaseSigningProperties.filter { it.second == null }.map { it.first }
val useReleaseKey = releaseStoreFile != null && releaseSigningUnset.isEmpty()
if (!useReleaseKey) {
    val partlyConfigured = releaseSigningUnset.size < releaseSigningProperties.size
    val problems = buildList {
        if (releaseStorePath != null && releaseStoreFile == null) {
            add("clineTermuxReleaseStoreFile names a missing file" +
                if (releaseStorePath.startsWith("~")) " (Gradle does not expand ~; use an absolute path)" else "")
        }
        if (releaseSigningUnset.isNotEmpty()) add("not set: ${releaseSigningUnset.joinToString()}")
    }
    val releaseSigningTasks = listOf("validateSigningRelease", "packageRelease", "signReleaseBundle").map { "${project.path}:$it" }
    gradle.taskGraph.whenReady {
        if (releaseSigningTasks.any { hasTask(it) }) {
            if (partlyConfigured) {
                throw GradleException(
                    "release signing is only partly configured: ${problems.joinToString("; ")}. Fix it, or " +
                        "unset all four clineTermuxRelease* properties to sign with the debug key.",
                )
            }
            logger.warn("release keystore not configured; signing with the debug key")
        }
    }
}

android {
    namespace = "dev.cline.termux"
    // Compose 1.12 / navigation 2.10 / lifecycle 2.11 / okhttp 5.5 AARs require
    // compileSdk >= 37; the S25U target stays API 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ichouchiang.clinetermux"
        minSdk = 26
        targetSdk = 36
        // versionCode stays 1 for the first public prerelease. Raise it with every later APK given
        // to testers: Android refuses to install an APK whose versionCode is lower than the
        // installed one's.
        versionCode = 1
        // First public prerelease (docs/release-notes/v0.1.0-pre.1.md). The About screen marks a
        // release "Installed" when this equals a `## <version>` heading in assets/CHANGELOG.md.
        versionName = "0.1.0-pre.1"
        vectorDrawables.useSupportLibrary = true
        // Compose UI flows of docs/07 §10.2 (app/src/androidTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            // R1 (docs/decided.md): the debug build installs beside the owner's own
            // io.github.ichouchiang.clinetermux instead of replacing it. Scripts start it as
            // io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity (classes keep the namespace).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug builds can be launched against the in-process fake kernel
            // (intent extra dev.cline.termux.FAKE_KERNEL) for Compose UI tests — docs/07 §10.2.
            buildConfigField("boolean", "FAKE_KERNEL", "true")
        }
        release {
            buildConfigField("boolean", "FAKE_KERNEL", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Used only by the jank bench (`-Pcline.releaseIdSuffix=.bench` → io.github.ichouchiang.clinetermux.bench),
            // so a release build never replaces the owner's io.github.ichouchiang.clinetermux on the phone.
            providers.gradleProperty("cline.releaseIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let { applicationIdSuffix = it }
            signingConfig = signingConfigs.getByName(if (useReleaseKey) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    testOptions {
        // Robolectric tests for the notifier and share helpers read real resources.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Every user-visible string lives in strings.xml (docs/07 §0 rule 7).
        error += listOf("HardcodedText")
        abortOnError = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    // Window size classes, rail/drawer suite and list-detail scaffolds (design-system.md §0, §5).
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.3.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Custom Tabs for sign-in and documentation links.
    implementation("androidx.browser:browser:1.9.0")
    // ProcessLifecycleOwner: keep-alive window and cursor flush on background.
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // Image attachment orientation.
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // PersistentList in UiStates.
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // CommonMark parser for assistant output; the Compose renderer is ours.
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.30.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
