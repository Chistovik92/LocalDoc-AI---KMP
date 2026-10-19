#!/usr/bin/env bash
# Сборка C-ядра LocalDoc (llama.cpp + localdoc_c_api) в статические библиотеки
# для iOS (устройство arm64 + симулятор arm64) и упаковка в XCFramework.
# Требуется macOS с Xcode 15+ и CMake 3.22+.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_DIR="${CORE_DIR}/build-ios"

MIN_IOS="16.0"
BUILD_TYPE="Release"

configure_and_build() {
    local sdk="$1" dir="$2"
    cmake -B "${CORE_DIR}/build/${dir}" -S "${CORE_DIR}" \
        -G Xcode \
        -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_OSX_DEPLOYMENT_TARGET="${MIN_IOS}" \
        -DCMAKE_OSX_SYSROOT="${sdk}" \
        -DCMAKE_XCODE_ATTRIBUTE_ONLY_ACTIVE_ARCH=YES \
        -DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="${CORE_DIR}/build/${dir}/out" \
        -DGGML_METAL=ON \
        -DGGML_METAL_EMBED_LIBRARY=ON \
        -DGGML_NATIVE=OFF \
        -DGGML_ACCELERATE=ON \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_BUILD_TOOLS=OFF \
        -DLLAMA_CURL=OFF \
        -DBUILD_SHARED_LIBS=OFF
    cmake --build "${CORE_DIR}/build/${dir}" --config "${BUILD_TYPE}" --target localdoc_core -j "$(sysctl -n hw.ncpu)"
}

# 1. Устройство (arm64)
configure_and_build iphoneos ios-device

# 2. Симулятор на Apple Silicon (arm64)
configure_and_build iphonesimulator ios-simulator

# 3. Собираем все статические библиотеки в один .a на платформу
# (генератор Xcode кладёт библиотеки в out/<CONFIG>/ независимо от SDK)
merge_libs() {
    local dir="$1" out="$2"
    mkdir -p "$(dirname "${out}")"
    libtool -static -o "${out}" \
        "${CORE_DIR}/build/${dir}/out/${BUILD_TYPE}/liblocaldoc_core.a"
    # Добавляем все архивы llama/ggml в тот же .a: набор бэкендов зависит от опций
    # (например, GGML_ACCELERATE даёт отдельный libggml-blas.a) — не перечисляем вручную.
    # libtool корректно обрабатывает как тонкие, так и fat-архивы, ar — нет.
    local src
    while IFS= read -r src; do
        echo "  + $(basename "${src}")"
        libtool -static -o "${out}" "${out}" "${src}"
    done < <(find "${CORE_DIR}/build/${dir}" -name "lib*.a" -path "*${BUILD_TYPE}*"                  ! -name "liblocaldoc_core.a" | awk -F/ '!seen[$NF]++' | sort)
    echo "Merged: ${out}"
}

merge_libs ios-device "${OUT_DIR}/device/liblocaldoc.a"
merge_libs ios-simulator "${OUT_DIR}/simulator/liblocaldoc.a"

# 4. Копируем заголовки
mkdir -p "${OUT_DIR}/include"
cp "${CORE_DIR}/bridge/localdoc_c_api.h" "${OUT_DIR}/include/"

# 5. XCFramework
rm -rf "${OUT_DIR}/localdoc_core.xcframework"
xcodebuild -create-xcframework \
    -library "${OUT_DIR}/device/liblocaldoc.a" -headers "${OUT_DIR}/include" \
    -library "${OUT_DIR}/simulator/liblocaldoc.a" -headers "${OUT_DIR}/include" \
    -output "${OUT_DIR}/localdoc_core.xcframework"

echo "Готово: ${OUT_DIR}/localdoc_core.xcframework"
