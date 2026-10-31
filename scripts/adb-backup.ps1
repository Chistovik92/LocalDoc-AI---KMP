# Бэкап данных приложения LocalDoc AI с подключённого устройства перед uninstall/переустановкой.
# Снимает копию внутренней памяти (/data/data/com.localdoc.ai) и внешней (/sdcard/Android/data/com.localdoc.ai)
# в папку backup-<дата> рядом со скриптом. Запускать ТОЛЬКО на дебаговых (run-as доступен) сборках:
# на release run-as закрыт, поэтому полный бэкап внутренней памяти недоступен — используйте adb backup или
# экспорт резервной копии из самого приложения.
#
# Пример:
#   powershell -ExecutionPolicy Bypass -File scripts/adb-backup.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/adb-backup.ps1 -Restore .\backup-20261101-001122

param(
    [switch]$Restore,
    [string]$Path = ""
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

$pkg = "com.localdoc.ai"
$internal = "/data/data/$pkg"
$external = "/sdcard/Android/data/$pkg"

# Убедимся, что устройство подключено
$devices = & $adb devices | Select-String "device$"
if (-not $devices) { Write-Error "Нет подключённого устройства."; exit 1 }

if ($Restore) {
    if (-not $Path) { Write-Error "Укажите -Path с папкой бэкапа."; exit 1 }
    Write-Host "Восстановление из $Path ..."
    # run-as копирует только файлы, к которым у приложения есть доступ (debug-сборки)
    & $adb push "$Path\internal" "$internal" 2>&1 | Out-Null
    & $adb shell "run-as $pkg cp -r /sdcard/tmp_restore/. $internal/ 2>/dev/null" 2>&1 | Out-Null
    & $adb push "$Path\external" "$external" 2>&1 | Out-Null
    Write-Host "Восстановлено (вручную: скопировать файлы через run-as)."
    exit 0
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path (Split-Path $MyInvocation.MyCommand.Path -Parent) "backup-$stamp"
New-Item -ItemType Directory -Path "$out\internal","$out\external" -Force | Out-Null

Write-Host "Бэкап $pkg -> $out"

# Внутренняя память: через run-as (только debug-сборки). Release: тарбол недоступен без root.
$runAs = & $adb shell "run-as $pkg ls /" 2>&1
if ($LASTEXITCODE -eq 0) {
    & $adb shell "run-as $pkg tar -cf /sdcard/localdoc_internal.tar -C $internal ." 2>&1 | Out-Null
    & $adb pull "/sdcard/localdoc_internal.tar" "$out\internal\data.tar" 2>&1 | Out-Null
    & $adb shell "rm /sdcard/localdoc_internal.tar" 2>&1 | Out-Null
    Write-Host "  internal: data.tar"
} else {
    Write-Warning "  internal: run-as недоступен (release-сборка). Внутренние данные НЕ сняты."
}

# Внешняя память (обновления и т.п.)
& $adb pull "$external" "$out\external" 2>&1 | Out-Null
Write-Host "  external: скопировано"

Write-Host "Готово: $out"
