import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val abis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

// Dev signing values are injected by GitHub Actions; no signing secrets live in the repo.
val devKeystorePath = System.getenv("NLIC_DEV_KEYSTORE_PATH")
val devStorePassword = System.getenv("NLIC_DEV_STORE_PASSWORD")
val devKeyPassword = System.getenv("NLIC_DEV_KEY_PASSWORD")
val devKeyAlias = System.getenv("NLIC_DEV_KEY_ALIAS")
val devSigningAvailable = listOf(
    devKeystorePath, devStorePassword, devKeyPassword, devKeyAlias
).all { !it.isNullOrBlank() }

// The production certificate is separate from the DEV identity and never
// committed to the repository. It is supplied only to an approved release job.
val releaseKeystorePath = System.getenv("NLIC_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("NLIC_RELEASE_STORE_PASSWORD")
val releaseKeyPassword = System.getenv("NLIC_RELEASE_KEY_PASSWORD")
val releaseKeyAlias = System.getenv("NLIC_RELEASE_KEY_ALIAS")
val releaseSigningAvailable = listOf(
    releaseKeystorePath, releaseStorePassword, releaseKeyPassword, releaseKeyAlias
).all { !it.isNullOrBlank() }

// Explicit opt-in for an internal, release-optimized TEST artifact.
// Uses the existing N-LIC DEV certificate; it is NOT a production signature.
// Do not publish as an official release. Permanent releases require nlicRelease.
val devSignedReleaseTest = System.getenv("NLIC_DEV_SIGN_RELEASE_TEST") == "true"

android {
    namespace = "io.github.romanvht.byedpi"
    //noinspection GradleDependency
    compileSdk = 36
    // Pin the native toolchain used by both Gradle/CMake and ndk-build.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.vanimusic6sudo.nlic"
        minSdk = 21
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = 1824
        versionName = "1.8.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(abis)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (devSigningAvailable) {
            create("nlicDev") {
                storeFile = file(requireNotNull(devKeystorePath))
                storePassword = devStorePassword
                keyAlias = devKeyAlias
                keyPassword = devKeyPassword
            }
        }
        if (releaseSigningAvailable) {
            create("nlicRelease") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            if (devSigningAvailable) {
                signingConfig = signingConfigs.getByName("nlicDev")
            }
        }
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("nlicRelease")
            } else if (devSignedReleaseTest && devSigningAvailable) {
                // Only the dedicated internal QA workflow sets this flag.
                signingConfig = signingConfigs.getByName("nlicDev")
            }
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }
}

dependencies {
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

tasks.preBuild {
    dependsOn("runNdkBuild")
}
