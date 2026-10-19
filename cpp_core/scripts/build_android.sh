#!/usr/bin/env bash
# Опциональная standalone-сборка C-ядра под Android (arm64) через NDK.
# Обычная сборка приложения делает это автоматически через Gradle externalNativeBuild.
# Использование: ANDROID_NDK_ROOT=/path/to/ndk ./build_android.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
: "${ANDROID_NDK_ROOT:?Укажите ANDROID_NDK_ROOT (путь до Android NDK)}"

# Vulkan опционален (требуется Vulkan SDK на хосте: glslc + SPIRV-Headers)
VULKAN_FLAGS=""
if [ "${LOCALDOC_VULKAN:-off}" = "on" ]; then
    VULKAN_FLAGS="-DGGML_VULKAN=ON"
fi

OUT_DIR="${CORE_DIR}/build-android/arm64-v8a"
TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt"
case "$(uname -s)" in
    Darwin) HOST_TAG="macosx-arm64"; [ -d "${TOOLCHAIN}/${HOST_TAG}" ] || HOST_TAG="macosx" ;;
    Linux)  HOST_TAG="linux-x86_64" ;;
    MINGW*) HOST_TAG="windows-x86_64" ;;
    *)      HOST_TAG="linux-x86_64" ;;
esac

cmake -B "${OUT_DIR}" -S "${CORE_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    ${VULKAN_FLAGS} \
    -DGGML_NATIVE=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_CURL=OFF

cmake --build "${OUT_DIR}" --config Release --target localdoc_core -j "$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
echo "Готово: ${OUT_DIR}"
