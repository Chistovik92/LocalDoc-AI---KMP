#!/usr/bin/env bash
# Скачивает статический ONNX Runtime C API для iOS.
# Версия должна совпадать с заголовками cpp_core/third_party/onnxruntime.
set -euo pipefail

VERSION="1.24.3"
SHA256="b7eedc45932bac758ffd057cac0feb3f682269e47750b159e4c865145cbf0a8e"

# Официальный хост download.onnxruntime.ai перестал отдавать архивы в ноябре 2026:
# репозиторий microsoft/onnxruntime удалён с GitHub, CDN отвечает 404 на любые пути
# (а его TLS-сертификат больше не называет этот домен — curl 60). Поэтому сначала
# пробуем официальный источник (вернёмся к нему автоматически, если апстрим оживёт),
# затем зеркала сообщества с тем же архивом. Подлинность в любом случае гарантирует
# пинненный SHA256: файл с другим содержимым не пройдёт проверку ниже. Хеш 1.24.3
# зафиксирован с живого официального артефакта до инцидента (epub_tool_rust, август
# 2026, официальный URL) и независимо подтверждён проектами pocketpal-ai и snapplan.
URLS=(
    "https://download.onnxruntime.ai/pod-archive-onnxruntime-c-${VERSION}.zip"
    "https://github.com/a-ghorbani/pocketpal-ai/releases/download/deps-onnxruntime-c-${VERSION}/pod-archive-onnxruntime-c-${VERSION}.zip"
    "https://github.com/baijum/ukulele-companion/releases/download/vendor-onnxruntime-c-${VERSION}/pod-archive-onnxruntime-c-${VERSION}.zip"
)

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

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    else
        sha256sum "$1" | cut -d ' ' -f 1
    fi
}

DOWNLOADED="0"
for url in "${URLS[@]}"; do
    echo "Источник: ${url}"
    if curl -fsSL --retry 2 --connect-timeout 20 -o "${TMP}/onnxruntime.zip" "${url}"; then
        ACTUAL="$(sha256_of "${TMP}/onnxruntime.zip")"
        if [ "${ACTUAL}" = "${SHA256}" ]; then
            DOWNLOADED="1"
            break
        fi
        echo "Контрольная сумма не совпала (${ACTUAL}), пробуем следующий источник" >&2
        rm -f "${TMP}/onnxruntime.zip"
    fi
done

if [ "${DOWNLOADED}" != "1" ]; then
    echo "Не удалось скачать onnxruntime-c ${VERSION} с корректным SHA256" >&2
    exit 1
fi

unzip -q "${TMP}/onnxruntime.zip" -d "${TMP}/x"
rm -rf "${TARGET}"
mv "${TMP}/x/onnxruntime.xcframework" "${TARGET}"
echo "${VERSION}" > "${STAMP}"
echo "Готово: ${TARGET}"
