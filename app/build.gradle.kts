import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aurawave.player"
    compileSdk = 36

    defaultConfig {
        // Not the id of the first AuraWave (com.aurawave.music, a web app in an Android shell):
        // the two are different apps to Android, and this one does not update that one.
        applicationId = "com.aurawave.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "3.2.0"
        // Phones only: every Android phone made in years is 64-bit ARM. Emulators (x86) and old 32-bit phones are left out.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives in signing/ (not in git). Without it, release builds are signed
    // with the debug key so they still install for testing, but cannot update a properly signed install.
    val signing = Properties().apply {
        rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (!signing.isEmpty) {
            create("release") {
                storeFile = rootProject.file("signing/${signing.getProperty("storeFile")}")
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio / java.time APIs that older Android versions lack.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // The database layout of every released version is kept, so upgrades can migrate people's libraries.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":innertube"))
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test-junit"))
}
