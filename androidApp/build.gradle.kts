import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

// AGP 9 встроенный Kotlin: JVM-цель задаётся через kotlin.compilerOptions
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
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
    // Сборка против API 37.1 (Android 17 QPR): этого требует Compose Multiplatform 1.13 (Material3 Expressive)
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.localdoc.ai"
        // Android 8.0 (API 26) … Android 17 (API 37)
        minSdk = 26
        targetSdk = 37
        versionCode = 31
        versionName = "2.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        debug {
            // Debug-сборка живёт рядом с release (com.localdoc.ai.debug), не конфликтуя с ней:
            // для проверки на устройстве без удаления релизных данных
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Lint-анализатор release-сборок несовместим с новыми метаданными зависимостей
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    // Compose-рантайм нужен приложению напрямую (setContent { App() }); версии совпадают с shared
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(libs.jetbrains.material3)
    // ManifestSecurityTest
    testImplementation(libs.kotlin.test.junit)
    // Запуск приложения, системный TTS и замеры RAG на Android-устройстве
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
