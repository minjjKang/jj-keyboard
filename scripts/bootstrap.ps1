param([switch]$AndroidSdk)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force -Path $taskTools | Out-Null

function Get-TaskDownload([string]$Url, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Host "Downloading $(Split-Path -Leaf $Destination)..."
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
}

$taskJava = Join-Path $taskTools 'jdk'
if (-not (Test-Path -LiteralPath "$taskJava\bin\java.exe")) {
    $taskRelease = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $taskPackage = @($taskRelease)[0].binary.package
    $taskZip = Join-Path $taskTools 'jdk17.zip'
    Get-TaskDownload $taskPackage.link $taskZip
    if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne $taskPackage.checksum) {
        throw 'JDK checksum mismatch.'
    }
    $taskExtract = Join-Path $taskTools 'jdk-extract'
    Expand-Archive -LiteralPath $taskZip -DestinationPath $taskExtract -Force
    $taskJdkDirectory = Get-ChildItem -LiteralPath $taskExtract -Directory | Select-Object -First 1
    Move-Item -LiteralPath $taskJdkDirectory.FullName -Destination $taskJava
}
$env:JAVA_HOME = $taskJava
$env:Path = "$taskJava\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-cache'

$taskGradle = Join-Path $taskTools 'gradle-8.13'
$taskGradleChecksum = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
if (-not (Test-Path -LiteralPath "$taskGradle\bin\gradle.bat")) {
    $taskZip = Join-Path $taskTools 'gradle-8.13-bin.zip'
    Get-TaskDownload 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' $taskZip
    if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash -ne $taskGradleChecksum) {
        throw 'Gradle checksum mismatch.'
    }
    Expand-Archive -LiteralPath $taskZip -DestinationPath $taskTools -Force
}

if ($AndroidSdk) {
    $taskSdk = Join-Path $taskTools 'android-sdk'
    $taskSdkManager = Join-Path $taskSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path -LiteralPath $taskSdkManager)) {
        $taskZip = Join-Path $taskTools 'android-commandline.zip'
        Get-TaskDownload 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $taskZip
        $taskExtract = Join-Path $taskTools 'android-extract'
        Expand-Archive -LiteralPath $taskZip -DestinationPath $taskExtract -Force
        New-Item -ItemType Directory -Force -Path "$taskSdk\cmdline-tools" | Out-Null
        Move-Item -LiteralPath "$taskExtract\cmdline-tools" -Destination "$taskSdk\cmdline-tools\latest"
    }
    $env:ANDROID_HOME = $taskSdk
    & $taskSdkManager "--sdk_root=$taskSdk" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed. Review SDK licenses using sdkmanager --licenses.' }
    if (-not (Test-Path -LiteralPath "$taskSdk\platforms\android-36\android.jar") -or
        -not (Test-Path -LiteralPath "$taskSdk\build-tools\35.0.0\aapt2.exe")) {
        throw 'Required SDK packages were not installed. Review and accept the Android SDK license, then rerun this script.'
    }
    $taskSdkPath = $taskSdk.Replace('\', '/')
    Set-Content -LiteralPath (Join-Path $taskRoot 'local.properties') -Value "sdk.dir=$taskSdkPath" -Encoding ASCII
}

& "$taskGradle\bin\gradle.bat" -p $taskRoot wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum $taskGradleChecksum
if ($LASTEXITCODE -ne 0) { throw 'Gradle wrapper generation failed.' }
Write-Host 'Environment ready. Run scripts\build.ps1.'
