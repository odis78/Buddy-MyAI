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

    // Side-by-side validation build. It cannot replace the installed stable
    // package and keeps its app data untouched.
    buildTypes {
        create("candidate") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".candidate"
            versionNameSuffix = "-candidate"
            matchingFallbacks += listOf("debug")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
