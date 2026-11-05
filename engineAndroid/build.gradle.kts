plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.localdoc.ai.engine"
    // Сборка против API 37.1 (Android 17 QPR): этого требует Compose Multiplatform 1.13 (Material3 Expressive)
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Android 8.0 (API 26) … Android 17 (API 37)
        minSdk = 26

        ndk {
            // 64-битные устройства (все современные) и 32-битные (armeabi-v7a: Android 8–9 на старых телефонах).
            // Для проверки на x86-эмуляторах: ./gradlew assembleDebug -Plocaldoc.abis=x86,x86_64
            val abis = providers.gradleProperty("localdoc.abis").orNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            abiFilters += abis ?: listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                // Vulkan включается локально: ./gradlew assembleDebug -Plocaldoc.vulkan=true
                // (требуется Vulkan SDK на хосте: glslc + SPIRV-Headers)
                val vulkan = providers.gradleProperty("localdoc.vulkan").orNull == "true"
                val sanitize = providers.gradleProperty("localdoc.sanitize").orNull == "true"
                arguments += buildList {
                    add("-DANDROID_STL=c++_shared")
                    // Библиотеки выравниваются на 16 КБ: без этого приложение не запускается на устройствах Android 15+ с 16-КБ страницами
                    add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                    if (vulkan) add("-DGGML_VULKAN=ON")
                    if (sanitize) add("-DLOCALDOC_SANITIZE=ON")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        // Lint-анализатор release-сборок несовместим с новыми метаданными зависимостей
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // NativeLlamaEngine: callbackFlow/Channel/Dispatchers.IO для потоковой генерации
    implementation(libs.kotlinx.coroutines.android)
}
