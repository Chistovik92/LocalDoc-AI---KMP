# Сэмплы голосов Piper

Короткие WAV-сэмплы для предпрослушивания голоса **без установки полного пакета**.
Приложение качает их с `raw.githubusercontent.com` и играет через встроенный PCM-вывод.

## Именование

Файл называется `<id голоса>.wav`, где `id` — идентификатор из каталога
`PiperVoiceCatalog` (поле `id` в `PiperVoices.kt`), например:

- `vits-piper-ru_RU-irina-medium.wav`
- `vits-piper-en_US-lessac-medium.wav`

## Как сгенерировать сэмпл

1. Скачать модель голоса (`.onnx` + `tokens.txt` + `espeak-ng-data`) из
   [sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models).
2. Синтезировать короткую фразу:

```bash
sherpa-onnx-offline-tts \
  --vits-model=./<voice>.onnx \
  --vits-tokens=./tokens.txt \
  --vits-data-dir=./espeak-ng-data \
  --output-filename=./<voice>.wav \
  "Привет. Так звучит этот голос."
```

3. Положить файл сюда и закоммитить.

## Формат

WAV (RIFF) PCM 16-бит или 32-бит float, 1–2 канала — декодируется встроенным `WavDecoder`.
Короткий (1–2 предложения), чтобы скачивался быстро.
