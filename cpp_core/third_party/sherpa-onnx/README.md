# sherpa-onnx C API

`c-api.h` — публичный заголовок [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(Apache-2.0, © Xiaomi Corporation) из `sherpa-onnx-v1.13.8-ios-shared-onnxruntime-static.xcframework.zip`.

Нужен Kotlin/Native cinterop (`composeApp/src/nativeInterop/cinterop/sherpa_onnx.def`) уже на этапе
компиляции, когда бинарный фреймворк ещё не скачан. Сам `SherpaOnnxC.xcframework` скачивает
`cpp_core/scripts/fetch_sherpa_onnx_ios.sh` (с проверкой sha256).

При обновлении версии заменить заголовок из нового архива и поправить версию и хэш в скрипте
и `sherpaOnnx` в `gradle/libs.versions.toml` (Android AAR).
