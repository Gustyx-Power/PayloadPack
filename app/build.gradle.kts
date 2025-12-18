plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.xms.payloadpack"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "id.xms.payloadpack"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ABI filter for modern devices (Poco F5 uses arm64-v8a)
            abiFilters += listOf("arm64-v8a")
        }
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // Add the jniLibs directory for our manually built Rust libraries
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// ============================================================
// Rust/Cargo-NDK Build Configuration
// ============================================================

// NDK Path resolution
val ndkDir: File? = android.ndkDirectory.takeIf { it.exists() }

// Rust source directory
val rustProjectDir = file("src/main/rust")

// Output directory for compiled .so files
val jniLibsDir = file("src/main/jniLibs")

// Map of Android ABI to Rust target triple
val abiToTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android"
)

// Register the cargo-ndk build task
tasks.register("cargoBuildNdk") {
    group = "rust"
    description = "Build Rust library using cargo-ndk"
    
    // Always run (check Cargo.lock for changes would be more complex)
    outputs.upToDateWhen { false }
    
    doLast {
        // Ensure jniLibs directories exist
        abiToTarget.keys.forEach { abi ->
            file("${jniLibsDir}/${abi}").mkdirs()
        }
        
        val cargoNdkArgs = mutableListOf(
            "cargo", "ndk",
            "--manifest-path", "${rustProjectDir}/Cargo.toml",
            "--target", "arm64-v8a",
            "--platform", "26",
            "--output-dir", jniLibsDir.absolutePath,
            "--",
            "build", "--release"
        )
        
        println("🦀 Building Rust library with cargo-ndk...")
        println("Command: ${cargoNdkArgs.joinToString(" ")}")
        
        exec {
            workingDir = rustProjectDir
            commandLine = cargoNdkArgs
            
            // Set ANDROID_NDK_HOME if NDK is found
            ndkDir?.let { ndk ->
                environment("ANDROID_NDK_HOME", ndk.absolutePath)
                println("ANDROID_NDK_HOME set to: ${ndk.absolutePath}")
            }
        }
        
        println("✅ Rust library built successfully!")
    }
}

// Ensure Rust is built before merging JNI libs
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    dependsOn("cargoBuildNdk")
}

// Also ensure it runs before the pre-build to catch errors early
tasks.matching { it.name.matches(Regex("pre.*Build")) }.configureEach {
    dependsOn("cargoBuildNdk")
}

// Clean task for Rust artifacts
tasks.register("cargoClean") {
    group = "rust"
    description = "Clean Rust build artifacts"
    
    doLast {
        exec {
            workingDir = rustProjectDir
            commandLine = listOf("cargo", "clean")
        }
        // Also clean jniLibs
        jniLibsDir.deleteRecursively()
        println("🧹 Rust artifacts cleaned")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Root shell operations
    implementation(libs.libsu.core)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}