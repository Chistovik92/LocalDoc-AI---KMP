#!/usr/bin/env bash
# Скачивает официальный статический ONNX Runtime C API для iOS.
# Версия должна совпадать с заголовками cpp_core/third_party/onnxruntime.
set -euo pipefail

VERSION="1.24.1"
SHA256="dab5e98ceba017cfcb74d4a03cc3ab3f0069ede02bc9334ee81ebee310e6e639"
URL="https://download.onnxruntime.ai/pod-archive-onnxruntime-c-${VERSION}.zip"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/build-ios"
TARGET="${OUT_DIR}/onnxruntime.xcframework"
STAMP="${TARGET}/.localdoc-version"

if [ -f "${STAMP}" ] && [ "$(cat "${STAMP}")" = "${VERSION}" ]; then
    echo "onnxruntime.xcframework ${VERSION} уже на месте"
    exit 0
fi

mkdir -p "${OUT_DIR}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

curl -fsSL --retry 4 -o "${TMP}/onnxruntime.zip" "${URL}"
ACTUAL="$(shasum -a 256 "${TMP}/onnxruntime.zip" | cut -d ' ' -f 1)"
if [ "${ACTUAL}" != "${SHA256}" ]; then
    echo "Контрольная сумма не совпала: ${ACTUAL}" >&2
    exit 1
fi

unzip -q "${TMP}/onnxruntime.zip" -d "${TMP}/x"
rm -rf "${TARGET}"
mv "${TMP}/x/onnxruntime.xcframework" "${TARGET}"
echo "${VERSION}" > "${STAMP}"
echo "Готово: ${TARGET}"
