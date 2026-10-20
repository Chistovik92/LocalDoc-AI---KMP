#!/usr/bin/env bash
# Скачивает готовый SherpaOnnxC.xcframework (sherpa-onnx, офлайн-синтез Piper) для iOS:
# динамический фреймворк с onnxruntime внутри — не конфликтует со статическим ядром llama.cpp.
# Версия должна совпадать с заголовком cpp_core/third_party/sherpa-onnx/c-api.h
# и с sherpaOnnx в gradle/libs.versions.toml (Android).
set -euo pipefail

VERSION="1.13.8"
SHA256="e259a7d3b38ad7dec49bb078252a30bb42ede8355e2bb130cf8c1c78ed131f75"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v${VERSION}-ios-shared-onnxruntime-static.xcframework.zip"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/build-ios"
TARGET="${OUT_DIR}/SherpaOnnxC.xcframework"
STAMP="${TARGET}/.version"

if [ -f "${STAMP}" ] && [ "$(cat "${STAMP}")" = "${VERSION}" ]; then
    echo "SherpaOnnxC.xcframework ${VERSION} уже на месте"
    exit 0
fi

mkdir -p "${OUT_DIR}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

curl -fsSL --retry 4 -o "${TMP}/sherpa.zip" "${URL}"
ACTUAL="$(shasum -a 256 "${TMP}/sherpa.zip" | cut -d ' ' -f 1)"
if [ "${ACTUAL}" != "${SHA256}" ]; then
    echo "Контрольная сумма не совпала: ${ACTUAL}" >&2
    exit 1
fi

unzip -q "${TMP}/sherpa.zip" -d "${TMP}/x"
rm -rf "${TARGET}"
mv "${TMP}/x/SherpaOnnxC.xcframework" "${TARGET}"
echo "${VERSION}" > "${STAMP}"
echo "Готово: ${TARGET}"
