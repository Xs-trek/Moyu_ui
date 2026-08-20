param(
    [string]$JdkHome = '',
    [string]$AndroidSdk = '',
    [string]$DebugKeystore = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\debug.keystore'),
    [string]$OutputApk = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app-arm64-release.apk')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $projectRoot 'android'

function Assert-NativeSuccess([string]$label) {
    if ($LASTEXITCODE -ne 0) { throw "$label failed with exit code $LASTEXITCODE" }
}

function Test-Jdk11OrNewer([string]$candidate) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { return $false }
    $java = Join-Path $candidate 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { return $false }
    $release = Join-Path $candidate 'release'
    if (Test-Path -LiteralPath $release -PathType Leaf) {
        $versionLine = Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
        if ($versionLine -match 'JAVA_VERSION="(?:1\.)?([0-9]+)') {
            return ([int]$Matches[1] -ge 11)
        }
    }
    $versionText = (& $java -version 2>&1 | Select-Object -First 1) -join ''
    if ($versionText -match 'version "(?:1\.)?([0-9]+)') { return ([int]$Matches[1] -ge 11) }
    return $false
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $JdkHome = @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jre',
        'C:\Software\DESKTOP\Major_tools\source\AndroidStudio\jre',
        $env:JAVA_HOME
    ) | Where-Object { Test-Jdk11OrNewer $_ } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk'),
        'C:\Software\DESKTOP\Major_tools\source\sdk'
    ) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'build-tools')) } | Select-Object -First 1
}

foreach ($requiredPath in @(
    (Join-Path $JdkHome 'bin\java.exe'),
    (Join-Path $AndroidSdk 'build-tools'),
    $DebugKeystore
)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Missing required path: $requiredPath" }
}
if (-not (Test-Jdk11OrNewer $JdkHome)) { throw "JDK 11 or newer is required: $JdkHome" }

& (Join-Path $PSScriptRoot 'verify-native-libs.ps1') -ProjectRoot $projectRoot
Push-Location $projectRoot
try {
    npm.cmd run build
    Assert-NativeSuccess 'npm run build'
    npm.cmd run check
    Assert-NativeSuccess 'npm run check'
} finally {
    Pop-Location
}

$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JdkHome
Push-Location $androidRoot
try {
    & '.\gradlew.bat' testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon
    Assert-NativeSuccess 'Gradle verification/release build'
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) { throw 'Android build-tools with apksigner were not found' }

$unsignedApk = Join-Path $androidRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsignedApk -PathType Leaf)) { throw "Missing unsigned release APK: $unsignedApk" }

$artifactRoot = Join-Path $androidRoot 'app\build\outputs\apk\verified'
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$alignedApk = Join-Path $artifactRoot 'moyu-arm64-release-aligned.apk'
$signedApk = Join-Path $artifactRoot 'moyu-arm64-release-test-signed.apk'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'

# AGP/zipflinger normally emits an already-aligned unsigned APK. Rewriting that file
# with an older standalone zipalign can discard its existing page-alignment metadata,
# so validate first and only rewrite when validation actually fails.
& $zipalign -c -p 4 $unsignedApk
if ($LASTEXITCODE -eq 0) {
    Copy-Item -LiteralPath $unsignedApk -Destination $alignedApk -Force
} else {
    & $zipalign -f -p 4 $unsignedApk $alignedApk
    Assert-NativeSuccess 'zipalign'
    & $zipalign -c -p 4 $alignedApk
    Assert-NativeSuccess 'zipalign verification'
}
& $apksigner sign --ks $DebugKeystore --ks-pass 'pass:android' --key-pass 'pass:android' --out $signedApk $alignedApk
Assert-NativeSuccess 'APK signing'
& $apksigner verify --verbose --print-certs $signedApk
Assert-NativeSuccess 'APK signature verification'

$badgingLines = & $aapt dump badging $signedApk
Assert-NativeSuccess 'APK badging inspection'
$packageLine = $badgingLines | Select-Object -First 1
if ($packageLine -notmatch "package: name='com\.moyu\.remote'" -or $packageLine -notmatch "versionName='0\.0\.3'") { throw "Unexpected package identity: $packageLine" }
if (-not ($badgingLines -match "application-label:'moyu'")) { throw 'Unexpected application label' }
if (-not ($badgingLines -match "native-code: 'arm64-v8a'")) { throw 'APK is not the arm64 Moyu build' }
$entries = & $aapt list $signedApk
Assert-NativeSuccess 'APK content inspection'
if ($entries -notcontains 'assets/ui/index.html') { throw 'Signed APK does not contain assets/ui/index.html' }
if ($entries -contains 'assets/ui/preview.html') { throw 'Signed APK unexpectedly contains preview.html' }
if ($entries -match 'assets/ui/assets/preview-' -or $entries -match 'mock-host') { throw 'Signed APK unexpectedly contains Mock Host assets' }
foreach ($brandAsset in @('assets/ui/assets/brands/anthropic.svg', 'assets/ui/assets/brands/openai.svg')) {
    if ($entries -notcontains $brandAsset) { throw "Signed APK is missing approved local brand asset $brandAsset" }
}
foreach ($entry in @(
    'assets/licenses/Apache-2.0.txt',
    'assets/licenses/EasyTier-LGPL-3.0.txt',
    'assets/licenses/GPL-3.0.txt',
    'assets/licenses/THIRD_PARTY_NOTICES.txt',
    'lib/arm64-v8a/libeasytier_android_jni.so',
    'lib/arm64-v8a/libeasytier_ffi.so'
)) {
    if ($entries -notcontains $entry) { throw "Signed APK is missing $entry" }
}

Copy-Item -LiteralPath $signedApk -Destination $OutputApk -Force
Write-Host "Packaged TEST-SIGNED release APK: $OutputApk"
Write-Host $packageLine
