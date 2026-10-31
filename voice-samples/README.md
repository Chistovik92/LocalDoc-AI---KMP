# Сэмплы голосов Piper

Короткие MP3-сэмплы для предпрослушивания голосов **без установки голосового пакета**.
Приложение получает их с `raw.githubusercontent.com` и проигрывает через платформенный декодер.

## Источник

Файлы взяты из официального репозитория голосов Piper:
**[rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices)** (Hugging Face),
путь внутри репозитория — `<lang>/<locale>/<speaker>/<quality>/samples/speaker_0.mp3`.

Дата взятия копии: **2026-10-31** (репозиторий `main`; файлы `speaker_0.mp3`).

## Именование

Файл называется `<id голоса>.mp3`, где `id` — идентификатор из каталога
`PiperVoiceCatalog` (поле `id` в `PiperVoices.kt`), например:

- `vits-piper-ru_RU-irina-medium.mp3`
- `vits-piper-en_US-lessac-medium.mp3`

URL, по которому приложение забирает сэмпл: `https://raw.githubusercontent.com/Chistovik92/LocalDoc-AI---KMP/main/voice-samples/<id>.mp3`.

## Формат

MP3 (MPEG-1 Layer III), моно/стерео, 22–44 кГц — декодируется платформенным
плеером приложения (`SampleAudio`: MediaPlayer на Android, AVAudioPlayer на iOS).
Короткие (несколько секунд), чтобы не расходовать трафик.
