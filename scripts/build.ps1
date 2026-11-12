param([string[]]$Tasks = @(':hangul:test', ':touch:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath "$taskRoot\.tools\jdk\bin\java.exe")) {
    $env:JAVA_HOME = "$taskRoot\.tools\jdk"
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$taskRoot\.tools\gradle-cache" }
if (-not (Test-Path -LiteralPath "$taskRoot\gradlew.bat")) {
    throw 'Run scripts\bootstrap.ps1 first, or generate a Gradle 8.13 wrapper in Android Studio.'
}
& "$taskRoot\gradlew.bat" -p $taskRoot @Tasks --console=plain
exit $LASTEXITCODE
