import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        compilations.all {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        iosTarget.compilations.getByName("main") {
            cinterops {
                val llama by creating {
                    defFile(project.file("src/nativeInterop/cinterop/llama.def"))
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ksoup)
            implementation(libs.ksoup.network)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
        }
    }
}

android {
    namespace = "com.localdoc.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localdoc.ai"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Современные флагманские устройства (Snapdragon 8 серии / Dimensity)
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                // Vulkan включается локально: ./gradlew assembleDebug -Plocaldoc.vulkan=true
                // (требуется Vulkan SDK на хосте: glslc + SPIRV-Headers)
                val vulkan = providers.gradleProperty("localdoc.vulkan").orNull == "true"
                arguments += buildList {
                    add("-DANDROID_STL=c++_shared")
                    if (vulkan) add("-DGGML_VULKAN=ON")
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = file("../signing/localdoc.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "localdoc"
                keyAlias = "localdoc"
                keyPassword = "localdoc"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (file("../signing/localdoc.keystore").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        // Lint-анализатор release-сборок несовместим с новыми метаданными зависимостей
        checkReleaseBuilds = false
        abortOnError = false
    }
}
