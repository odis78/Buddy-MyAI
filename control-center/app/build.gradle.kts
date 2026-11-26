plugins {
    id("com.android.application")
}

android {
    namespace = "app.jiti.controlcenter"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.jiti.controlcenter"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The 0.2.1 validation APK uses the production applicationId.
    // Install it only after intentionally uninstalling the preserved 0.2.0 checkpoint.
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
