import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // Release-линковка iOS-фреймворка на CI-раннере (macos-15, ~7 ГБ RAM) падала с
        // OutOfMemoryError на фазе DevirtualizationAnalysis (см. KT-78518). Отключаем только
        // эту фазу для release — пик памяти LTO заметно ниже, корректность не страдает.
        iosTarget.binaries.filterIsInstance<Framework>().forEach { binary ->
            if (binary.buildType == NativeBuildType.RELEASE) {
                binary.freeCompilerArgs += "-Xdisable-phases=DevirtualizationAnalysis"
            }
        }
        iosTarget.compilations.getByName("main") {
            cinterops {
                val llama by creating {
                    defFile(project.file("src/nativeInterop/cinterop/llama.def"))
                    compilerOpts("-I${project.rootProject.projectDir}/cpp_core/bridge")
                }
                // Офлайн-синтез Piper: C API sherpa-onnx (фреймворк линкует Xcode)
                val sherpaOnnx by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sherpa_onnx.def"))
                    compilerOpts("-I${project.rootProject.projectDir}/cpp_core/third_party/sherpa-onnx")
                }
                // Семантический поиск multilingual-e5-small: официальный ONNX Runtime C API
                val onnxruntime by creating {
                    defFile(project.file("src/nativeInterop/cinterop/onnxruntime.def"))
                    compilerOpts("-I${project.rootProject.projectDir}/cpp_core/third_party/onnxruntime")
                }
                // Системная libbz2 — распаковка голосовых пакетов
                val bzip2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/bzip2.def"))
                }
            }
        }
    }

    android {
        namespace = "com.localdoc.ai.shared"
        // Сборка против API 37.1 (Android 17 QPR): этого требует Compose Multiplatform 1.13 (Material3 Expressive)
        compileSdk {
            version = release(37) {
                minorApiLevel = 1
            }
        }
        // Android 8.0 (API 26)
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-jvm-default=no-compatibility")
        }
        // Compose-ресурсы (commonMain/composeResources) пакетаются в AAR
        androidResources {
            enable = true
        }
        // Юнит-тесты на JVM: androidHostTest включает commonTest
        withHostTest {
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            // Material3 Expressive: алиас compose.material3 в CMP 1.13.0-beta02 ведёт на 1.9.0 (API internal),
            // поэтому обёртка пиннуется явно (MaterialExpressiveTheme/MotionScheme.expressive() публичны в 1.13.0-beta02).
            implementation(libs.jetbrains.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ksoup)
            implementation(libs.ksoup.network)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // JNI-мост к llama.cpp (engineAndroid) — автономный модуль с C++/NDK
            implementation(project(":engineAndroid"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            // Material3 Expressive на Android: обёртка 1.13.0-beta02 уже тянет androidx material3 1.5.0-beta02,
            // где API публичны; пин оставлен, чтобы прямые ссылки на androidx.compose.material3 резолвились явно.
            implementation(libs.androidx.material3)
            // Фоновая проверка новых глав по расписанию
            implementation(libs.androidx.work)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
            // ONNX Runtime для эмбеддингов multilingual-e5-small
            implementation(libs.onnxruntime.android)
            // sherpa-onnx: JNI + Kotlin API OfflineTts, onnxruntime слинкован статически
            implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
            // bzip2 для распаковки голосовых пакетов (tar разбирает общий TarReader)
            implementation(libs.commons.compress)
        }
    }
}

compose.resources {
    packageOfResClass = "com.localdoc.ai.resources"
    generateResClass = always
}
