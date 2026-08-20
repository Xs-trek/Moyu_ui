param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$nativeRoot = Join-Path $ProjectRoot 'android\app\src\main\jniLibs\arm64-v8a'
$expected = [ordered]@{
    'libeasytier_android_jni.so' = 'B92A64620D084F9511AD03701CE9A9F62FF23268B8137B6A8337D367FE287BA9'
    'libeasytier_ffi.so' = 'C4B7B42C6EB809869AAD9CFAFD2AE5877BFAC0AB827416FB6F2F525F457407D5'
}

foreach ($name in $expected.Keys) {
    $path = Join-Path $nativeRoot $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing native library: $path"
    }
    # Use the .NET primitive rather than Get-FileHash so the release gate also works in
    # minimal Windows PowerShell hosts where that optional cmdlet is unavailable.
    $stream = [System.IO.File]::OpenRead($path)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $actual = [System.BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '')
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
    if ($actual -ne $expected[$name]) {
        throw "SHA-256 mismatch for ${name}: expected $($expected[$name]), got $actual"
    }
    Write-Host "verified $name $actual"
}
