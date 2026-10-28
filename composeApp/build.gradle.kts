import java.util.Properties
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
                    compilerOpts("-I${project.rootProject.projectDir}/cpp_core/bridge")
                }
                // Офлайн-синтез Piper: C API sherpa-onnx (фреймворк линкует Xcode)
                val sherpaOnnx by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sherpa_onnx.def"))
                    compilerOpts("-I${project.rootProject.projectDir}/cpp_core/third_party/sherpa-onnx")
                }
                // Системная libbz2 — распаковка голосовых пакетов
                val bzip2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/bzip2.def"))
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
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            // Фоновая проверка новых глав по расписанию
            implementation(libs.androidx.work)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.play.services)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
            // ONNX Runtime для эмбеддингов multilingual-e5-small; объявлен раньше sherpa-onnx, чтобы при общем
            // libonnxruntime.so (см. packaging ниже) выбиралась именно эта, более новая сборка
            implementation(libs.onnxruntime.android)
            // sherpa-onnx: JNI + Kotlin API OfflineTts, onnxruntime слинкован статически
            implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
            // bzip2 для распаковки голосовых пакетов (tar разбирает общий TarReader)
            implementation(libs.commons.compress)
        }
    }
}

/**
 * Постоянный ключ подписи release-сборок. Источники (по приоритету):
 *  1) переменные окружения CI: ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD,
 *     ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD (заполняются из GitHub Secrets);
 *  2) файл signing.properties: путь из -Plocaldoc.signing=..., по умолчанию
 *     ~/.android-signing/localdoc-ai/signing.properties (вне репозитория).
 * Ключ и пароли в репозиторий не попадают.
 */
data class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun loadReleaseSigning(): ReleaseSigning? {
    val env = System.getenv()
    env["ANDROID_KEYSTORE_FILE"]?.takeIf { it.isNotBlank() }?.let { path ->
        return ReleaseSigning(
            storeFile = file(path),
            storePassword = env["ANDROID_KEYSTORE_PASSWORD"].orEmpty(),
            keyAlias = env["ANDROID_KEY_ALIAS"].orEmpty(),
            keyPassword = env["ANDROID_KEY_PASSWORD"].orEmpty()
        )
    }
    val propsFile = providers.gradleProperty("localdoc.signing").orNull?.let { file(it) }
        ?: File(System.getProperty("user.home"), ".android-signing/localdoc-ai/signing.properties")
    if (!propsFile.exists()) return null
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    return ReleaseSigning(
        storeFile = propsFile.resolveSibling(props.getProperty("keystore.file")),
        storePassword = props.getProperty("keystore.password"),
        keyAlias = props.getProperty("key.alias"),
        keyPassword = props.getProperty("key.password")
    )
}

val releaseSigning = loadReleaseSigning()

android {
    namespace = "com.localdoc.ai"
    // API 37 (Android 17) AGP 8.7 не собирает (платформа SDK называется «android-37.0»): приложение работает на Android 17
    // через обратную совместимость (targetSdk 35); сборка против 37 — после обновления AGP
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localdoc.ai"
        // Android 8.0 (API 26) … Android 17 (API 37)
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "2.1.0"

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
                arguments += buildList {
                    add("-DANDROID_STL=c++_shared")
                    // Библиотеки выравниваются на 16 КБ: без этого приложение не запускается на устройствах Android 15+ с 16-КБ страницами
                    add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                    if (vulkan) add("-DGGML_VULKAN=ON")
                }
            }
        }
    }

    packaging {
        // onnxruntime-android (эмбеддинги) и sherpa-onnx (озвучка) приносят каждый свой libonnxruntime.so:
        // оставляем один — из onnxruntime-android 1.22; sherpa-onnx слинкован с ONNX Runtime статически
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Без ключа release-APK собирается неподписанным (CI на теге это запрещает)
            if (releaseSigning != null) {
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

compose.resources {
    packageOfResClass = "com.localdoc.ai.resources"
    generateResClass = always
}
