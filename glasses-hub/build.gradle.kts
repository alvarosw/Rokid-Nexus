plugins {
    id("com.android.application")
    id("io.github.takahirom.roborazzi")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.glasses"
        minSdk = 31
        targetSdk = 32
        versionCode = 10500
        versionName = "1.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // YodaOS side-load compatibility intentionally keeps this glasses app on API 32.
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests {
            // Roborazzi's screenshots launch a debug-only Activity via ActivityScenario, which
            // needs Robolectric to resolve it against the real merged manifest (debug source set
            // included) rather than a synthetic "org.robolectric.default" package — this flag is
            // what makes AGP build and hand Robolectric that real manifest/resources package.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":bus-client"))
    implementation(project(":ink-engine"))
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.airbnb.android:lottie:6.7.1")
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // Plain-JUnit ink tests exercise wire JSON against the real org.json, not the
    // throwing android.jar stubs â€” same arrangement as :shared.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    // Manual verification / visual-regression screenshots for the grid HUD (see
    // GridLauncherScreenshotTest): plain Robolectric NATIVE-graphics view.draw(canvas) calls
    // don't composite multi-child view trees correctly in this Robolectric version — Roborazzi
    // exists specifically to work around that, driving the capture through the real Espresso/
    // ActivityScenario window-attached rendering path instead.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.75.0")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
}
