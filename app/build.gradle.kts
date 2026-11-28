plugins {
    id("com.android.application")
}

// Version lives in BuildInfo.java so the Gradle and on-device (build.sh) builds always agree.
val buildInfo = file("src/main/java/app/buddy/assistant/BuildInfo.java").readText()
val appVersionName = Regex("""VERSION = "([^"]+)"""").find(buildInfo)!!.groupValues[1]
val appVersionCode = Regex("""VERSION_CODE = (\d+)""").find(buildInfo)!!.groupValues[1].toInt()

android {
    // proot for the built-in Linux ships as native libraries and must be extracted to disk to run
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    namespace = "app.buddy.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.buddy.myai"
        buildConfigField("int", "MCP_PORT", "8766")
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Release signing comes from environment variables (set as GitHub Actions secrets).
    // Without them, assembleRelease produces an unsigned APK.
    val keystorePath = System.getenv("BUDDY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BUDDY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUDDY_KEY_ALIAS") ?: "buddy"
                keyPassword = System.getenv("BUDDY_KEY_PASSWORD") ?: System.getenv("BUDDY_KEYSTORE_PASSWORD")
            }
        }
    }

    // github: the release on GitHub, which updates itself from GitHub releases.
    // fdroid: built and signed by F-Droid from this source; the same app without the self-updater (F-Droid updates it).
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("fdroid") { dimension = "store" }
    }

    // No Google-only dependency blob and no git details in the APK: F-Droid requires the first, and both keep the
    // fdroid APK the same whoever builds it (F-Droid's reproducible build compares its APK with the one on GitHub).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            // Keep this test build beside both the installed release and the previous test APK.
            applicationIdSuffix = ".test219"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

private fun sha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

val pinnedRuntimePayloads = mapOf(
    "src/main/jniLibs/arm64-v8a/libtunnel-client.so" to "f7c651958ffebf6e495d72f1406ee7603b6ebab3b77c211f9071e4b54904fb0e",
    "src/main/assets/runtime/rea-agents-6.5.0.tgz" to "531717a06e92ca651aab52691190f916fac044272c01f875e22c5e293218e699",
    "src/main/assets/runtime/termux-bridge-0.1.3.mjs" to "de8b033446e82b3e54570d681151cae5a8df2ef16e22f5d9bcc2757ecd0bb292",
    "src/main/assets/runtime/droidmcp-filesystem-0.4.0.mjs" to "75156254216e1b524d9e5eaac03fe113b432529a9bd1009e8698558c8d03a265"
)

val verifyAutonomousRuntimePayload by tasks.registering {
    group = "verification"
    description = "Fail closed if a pinned autonomous runtime payload is missing or has changed."
    doLast {
        pinnedRuntimePayloads.forEach { (relativePath, expectedSha256) ->
            val payloadFile = file(relativePath)
            if (!payloadFile.isFile) {
                throw GradleException("Missing pinned runtime payload: $relativePath. Build through the autonomous-runtime workflow.")
            }
            val actualSha256 = sha256(payloadFile)
            if (actualSha256 != expectedSha256) {
                throw GradleException("Pinned runtime payload digest mismatch for $relativePath: expected $expectedSha256, got $actualSha256")
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyAutonomousRuntimePayload)
}
