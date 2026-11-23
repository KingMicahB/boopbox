plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cardplayer"
    compileSdk = 36

    defaultConfig {
        // The Google Play app. (The sideload build with YouTube downloading is archived in archive/full-flavor/.)
        applicationId = "com.boopboxapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.1.0"
    }

    buildTypes {
        // Installed next to the Play app (com.boopboxapp.debug, "BoopBox test"): a debug-signed build can't
        // replace the Play-signed one, and uninstalling that one would take its cards with it.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8: shrinks, optimises and renames the code. Google Play asks for it (it reported "Obfuscation 0%"
            // for 1.0.5, with a warning about visibility), and the download gets smaller. What only breaks once
            // code is renamed shows in the releaseCheck build below, on a phone, before anything is uploaded.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build exactly as dexed for Play, but debug-signed and installed next to the Play app
        // (com.boopboxapp.check), so release-only bugs (like D8's VerifyError in the card editor) can be
        // checked on a phone: ./gradlew.bat assembleReleaseCheck
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".check"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Plain JVM tests (the audiobook chapter reader): android.* stubs return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
        // The sticker renderer test draws real stickers (Robolectric's native graphics), fonts and logo included.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // Only for CarConnection: is the phone connected to a car screen (Android Auto) right now?
    implementation(libs.androidx.car.app)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.billing)
    // Play Billing drags in fragment 1.1, which breaks ActivityResult permission requests (release lint error).
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
