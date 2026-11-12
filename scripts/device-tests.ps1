param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string[]]$TestName = @(),
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskAdb = Join-Path $taskRoot '.tools\android-sdk\platform-tools\adb.exe'
$taskRunLabel = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ($DeviceSerial -replace '[^A-Za-z0-9_-]', '_')
$taskOutput = Join-Path $taskRoot "docs\device-runs\$taskRunLabel"
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
if (-not $SkipBuild) {
    & "$PSScriptRoot\build.ps1" -Tasks ':app:assembleDebug', ':app:assembleDebugAndroidTest'
    if ($LASTEXITCODE -ne 0) { throw 'Device test APK build failed.' }
}
function Invoke-TaskAdb {
    & $taskAdb -s $DeviceSerial @args
    if ($LASTEXITCODE -ne 0) { throw "adb command failed: $args" }
}
$taskIme = (Invoke-TaskAdb shell settings get secure default_input_method | Out-String).Trim()
$taskOriginalEnabledImes = (Invoke-TaskAdb shell settings get secure enabled_input_methods | Out-String).Trim()
$taskAutoRotate = (Invoke-TaskAdb shell settings get system accelerometer_rotation | Out-String).Trim()
$taskRotation = (Invoke-TaskAdb shell settings get system user_rotation | Out-String).Trim()
$taskDeviceInfo = [ordered]@{
    serial = $DeviceSerial
    model = (Invoke-TaskAdb shell getprop ro.product.model | Out-String).Trim()
    android = (Invoke-TaskAdb shell getprop ro.build.version.release | Out-String).Trim()
    api = (Invoke-TaskAdb shell getprop ro.build.version.sdk | Out-String).Trim()
    originalIme = $taskIme
    originalAutoRotate = $taskAutoRotate
    originalRotation = $taskRotation
}
$taskDeviceInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'device.json') -Encoding UTF8
$taskSelector = 'io.github.minjj.keyboard.testing.RealImeTest'
if ($TestName) {
    foreach ($taskMethod in $TestName) {
        if ($taskMethod -notmatch '^[A-Za-z][A-Za-z0-9_]*$') { throw 'Use test method names without shell syntax.' }
    }
    $taskSelector = ($TestName | ForEach-Object { "io.github.minjj.keyboard.testing.RealImeTest#$_" }) -join ','
}
try {
    Invoke-TaskAdb install -r "$taskRoot\app\build\outputs\apk\debug\app-debug.apk"
    Invoke-TaskAdb install -r "$taskRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    # Replacing the currently selected IME can leave a stale service binding on Android 14.
    # Select another already-enabled IME before instrumentation selects Minjj again.
    if ($taskIme -eq 'io.github.minjj.keyboard/.ime.MinjjImeService') {
        $taskEnabledImes = (Invoke-TaskAdb shell settings get secure enabled_input_methods | Out-String).Trim()
        $taskInstalledImes = @(Invoke-TaskAdb shell ime list -s | ForEach-Object { $_.Trim() })
        $taskFallbackIme = $taskEnabledImes -split ':' | ForEach-Object { ($_ -split ';')[0] } |
            Where-Object { $_ -ne $taskIme -and $_ -ne 'null' -and $_ -in $taskInstalledImes -and $_ -match '^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$' } |
            Select-Object -Last 1
        if ($taskFallbackIme) { Invoke-TaskAdb shell ime set $taskFallbackIme }
        else {
            # With only our IME installed there is no fallback to break the stale binding.
            # Clear selection temporarily; setup selects it again and finally restores it.
            Invoke-TaskAdb shell settings delete secure default_input_method
            Invoke-TaskAdb shell ime disable io.github.minjj.keyboard/.ime.MinjjImeService
        }
    }
    $taskLog = Invoke-TaskAdb shell am instrument -w -r -e artifactRun $taskRunLabel -e class $taskSelector io.github.minjj.keyboard.test/androidx.test.runner.AndroidJUnitRunner |
        Tee-Object -FilePath (Join-Path $taskOutput 'instrumentation.txt')
    Invoke-TaskAdb pull "/sdcard/Android/data/io.github.minjj.keyboard/files/device-tests/$taskRunLabel" "$taskOutput\screens"
    if (($taskLog -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw "Device test failed. See $taskOutput\instrumentation.txt" }
    Write-Host "Device tests passed: $taskOutput"
} finally {
    # UiDevice freezes rotation during tests; restore the user's values and prior input method.
    Invoke-TaskAdb shell wm user-rotation free
    if ($taskRotation -match '^[0-3]$') { Invoke-TaskAdb shell settings put system user_rotation $taskRotation }
    if ($taskAutoRotate -match '^[01]$') { Invoke-TaskAdb shell settings put system accelerometer_rotation $taskAutoRotate }
    if ($taskOriginalEnabledImes -and $taskOriginalEnabledImes -ne 'null' -and $taskOriginalEnabledImes -match '^[A-Za-z0-9_.$/;:\-]+$') {
        Invoke-TaskAdb shell settings put secure enabled_input_methods "'$taskOriginalEnabledImes'"
    }
    if ($taskIme -and $taskIme -ne 'null') {
        Invoke-TaskAdb shell ime enable $taskIme
        Invoke-TaskAdb shell ime set $taskIme
    }
    # Keep only the local fixture visible between development runs, without changing timeout/lock settings.
    Invoke-TaskAdb shell am start -n io.github.minjj.keyboard/.testing.ImeTestActivity
}
