plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes -PappVersionName=0.1.0 (taken from the git tag). The version code is
// derived from it so that a newer tag always installs over an older one.
val appVersionName = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"
val appVersionCode = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }.let { parts ->
    val (major, minor, patch) = List(3) { parts.getOrElse(it) { 0 } }
    major * 10000 + minor * 100 + patch
}.coerceAtLeast(1)

android {
    namespace = "cz.antiscrol.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.antiscrol.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField(
            "String",
            "RULES_URL",
            "\"https://vitduchecek-afk.github.io/AntiScrol/rules.json\"",
        )
    }

    signingConfigs {
        // Test key committed to the repository on purpose: every build is signed
        // with the same key, so updates install over each other. Not a secret.
        create("shared") {
            storeFile = rootProject.file("keystore/antiscrol-test.jks")
            storePassword = "antiscrol"
            keyAlias = "antiscrol"
            keyPassword = "antiscrol"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // docs/rules.json is served by GitHub Pages and bundled as the default.
            assets.srcDir(rootProject.file("docs"))
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
