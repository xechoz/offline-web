import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.xechoz.offlineweb.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xechoz.offlineweb.demo"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "OFFLINE_PUBLIC_KEY",
            "\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEeP3wAyAVjU3KJ3F1SAzgG3r/WVMEecQD+e/DnZrgjFCWDbkXYvsRvjERcw+L7mC0QROwXT1X/qdxSupKP8LWIQ==\"",
        )
        buildConfigField("String", "OFFLINE_ENDPOINT", "\"http://10.0.2.2:8080/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
