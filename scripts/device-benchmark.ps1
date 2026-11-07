[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$OutputDirectory = 'benchmark-results',
    [switch]$RequirePhysicalDevice
)

$ErrorActionPreference = 'Stop'
$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adb -and $env:ANDROID_HOME) {
    $adb = Get-Command (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -ErrorAction SilentlyContinue
}
if ($null -eq $adb -and $env:LOCALAPPDATA) {
    $adb = Get-Command (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') -ErrorAction SilentlyContinue
}
if ($null -eq $adb) { throw 'adb не найден. Добавьте platform-tools в PATH или задайте ANDROID_HOME.' }

$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$devices = & $adb.Source @adbArgs get-state 2>$null
if ($LASTEXITCODE -ne 0 -or "$devices".Trim() -ne 'device') {
    throw 'Нет готового устройства. Подключите физический Android по USB с отладкой или задайте -Serial.'
}

$props = @{}
foreach ($name in @('ro.product.model', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.boot.hardware', 'ro.product.device')) {
    $props[$name] = (& $adb.Source @adbArgs shell getprop $name).Trim()
}
$isEmulator = @('ro.product.model', 'ro.boot.hardware', 'ro.product.device') |
    ForEach-Object { $props[$_] } |
    Where-Object { $_ -match 'sdk|emulator|goldfish|ranchu|generic' }
if ($RequirePhysicalDevice -and $isEmulator) { throw 'Нужен физический телефон: обнаружены признаки эмулятора.' }

& .\gradlew.bat :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
if ($LASTEXITCODE -ne 0) { throw 'Сборка device-тестов не удалась.' }
& $adb.Source @adbArgs install -r -t androidApp\build\outputs\apk\debug\androidApp-debug.apk
& $adb.Source @adbArgs install -r -t androidApp\build\outputs\apk\androidTest\debug\androidApp-debug-androidTest.apk
if ($LASTEXITCODE -ne 0) { throw 'Установка APK не удалась. Данные приложения не удалялись.' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$destination = Join-Path $OutputDirectory $stamp
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$log = Join-Path $destination 'instrumentation.log'
& $adb.Source @adbArgs shell am instrument -w -r -e class com.localdoc.ai.SemanticSearchBenchmark com.localdoc.ai.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 |
    Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw "Device-тест завершился с ошибкой; см. $log" }

$deviceDirectory = '/sdcard/Android/data/com.localdoc.ai.debug/files/benchmarks'
$files = & $adb.Source @adbArgs shell ls -t $deviceDirectory
$latest = $files | Where-Object { $_ -match '\.json$' } | Select-Object -First 1
if (-not $latest) { throw "JSON замера не найден на устройстве; см. $log" }
& $adb.Source @adbArgs pull "$deviceDirectory/$latest" $destination

$metadata = [ordered]@{
    timestamp = (Get-Date).ToUniversalTime().ToString('o')
    model = $props['ro.product.model']
    api = $props['ro.build.version.sdk']
    abi = $props['ro.product.cpu.abi']
    physicalDevice = -not [bool]$isEmulator
    requirePhysicalDevice = [bool]$RequirePhysicalDevice
    benchmark = 'hashing-rag; ONNX-модель не скачивается'
}
$metadata | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $destination 'device.json')
Write-Host "Результаты: $destination. Это не замер ONNX e5 и не основание менять SemanticModelPolicy."
