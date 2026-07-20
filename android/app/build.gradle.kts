plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amsynth.enhanced"
    compileSdk = 34

    // Pin to an NDK that the build host has installed (AGP's bundled default
    // may not be present). Override with -PndkVersion= if needed.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.amsynth.enhanced"
        minSdk = 24
        targetSdk = 34
        versionCode = 50600
        versionName = "5.6"

        ndk {
            // arm64 is the Pi-class / modern-phone target; the rest are for
            // emulators and older devices. Trim to taste to shrink the APK.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true // expose Oboe's prefab package to CMake
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Kotlin sources live under kotlin/ rather than java/.
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
