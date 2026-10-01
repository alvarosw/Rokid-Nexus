plugins {
    id("com.android.library")
}

// The grid tile renderer both hubs draw with; not part of the plugin SDK.
android {
    namespace = "com.anezium.rokidbus.hudtiles"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":shared"))
    implementation(project(":bus-client"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
