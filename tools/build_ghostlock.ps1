[CmdletBinding()]
param(
    [string]$NdkVersion = '29.0.13113456',
    [int]$AndroidApi = 35,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$buildRoot = $projectRoot
$junctionPath = $null

if ($projectRoot -match '[^\x00-\x7F]') {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $junctionPath = Join-Path $tempRoot "yhroot-ghostlock-build-$PID"
    if (Test-Path -LiteralPath $junctionPath) {
        throw "Temporary build path already exists: $junctionPath"
    }
    New-Item -ItemType Junction -Path $junctionPath -Target $projectRoot | Out-Null
    $junction = Get-Item -LiteralPath $junctionPath -Force
    if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Temporary build path is not a directory junction: $junctionPath"
    }
    $buildRoot = $junctionPath
}

try {
    $vendorRoot = Join-Path $buildRoot 'third_party\ghostlock'
    $jniRoot = Join-Path $buildRoot 'assistant\src\main\jniLibs\arm64-v8a'
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    $ndkRoot = $env:ANDROID_NDK_HOME
    if (-not $ndkRoot -and $sdkRoot) { $ndkRoot = Join-Path $sdkRoot "ndk\$NdkVersion" }
    if (-not $ndkRoot -or -not (Test-Path -LiteralPath $ndkRoot)) {
        throw "Android NDK $NdkVersion was not found. Set ANDROID_HOME or ANDROID_NDK_HOME."
    }

    $prebuilt = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'windows-x86_64' } else { 'linux-x86_64' }
    $binRoot = Join-Path $ndkRoot "toolchains\llvm\prebuilt\$prebuilt\bin"
    $clangName = if ($prebuilt -eq 'windows-x86_64') { "aarch64-linux-android$AndroidApi-clang.cmd" } else { "aarch64-linux-android$AndroidApi-clang" }
    $clang = Join-Path $binRoot $clangName
    if (-not (Test-Path -LiteralPath $clang)) { throw "NDK clang not found: $clang" }

    $responseFile = Join-Path $vendorRoot '.ghostlock-clang.rsp'
    $clangArgs = @(
        '-O2', '-flto', '-Wall', '-Wno-unused-parameter', '-Wno-sign-compare', '-Wno-unused-function',
        '-Isrc/core', '-Isrc/kernels', '-DTARGET_CONFIG_H=target.h',
        '-fPIE', '-pie', '-pthread', '-flto',
        'src/core/main.c', 'src/core/offsets_json.c', 'src/core/util.c', 'src/core/fops.c',
        'src/core/mcast_route.c',
        '-o', 'ghostlock'
    )
    [IO.File]::WriteAllLines($responseFile, $clangArgs, (New-Object Text.UTF8Encoding($false)))
    try {
        Push-Location -LiteralPath $vendorRoot
        try { & $clang '@.ghostlock-clang.rsp' } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "GhostLock C build failed with exit code $LASTEXITCODE" }
    } finally {
        if (Test-Path -LiteralPath $responseFile) { Remove-Item -LiteralPath $responseFile -Force }
    }

    $extractRoot = Join-Path $vendorRoot 'tools\extract_rs'
    $env:CC_aarch64_linux_android = $clang
    $env:AR_aarch64_linux_android = Join-Path $binRoot ($(if ($prebuilt -eq 'windows-x86_64') { 'llvm-ar.exe' } else { 'llvm-ar' }))
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
    $cargoArgs = @()
    if ($prebuilt -eq 'windows-x86_64') { $cargoArgs += '+nightly-x86_64-pc-windows-gnu' }
    $cargoArgs += @('build', '--release', '--target', 'aarch64-linux-android', '--locked')
    if ($Offline) { $cargoArgs += '--offline' }
    Push-Location -LiteralPath $extractRoot
    try { & cargo @cargoArgs } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "GhostLock extractor build failed with exit code $LASTEXITCODE" }

    New-Item -ItemType Directory -Path $jniRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $vendorRoot 'ghostlock') -Destination (Join-Path $jniRoot 'libghostlock.so') -Force
    Copy-Item -LiteralPath (Join-Path $extractRoot 'target\aarch64-linux-android\release\ghostlock-extract') -Destination (Join-Path $jniRoot 'libextract.so') -Force

    foreach ($name in @('libghostlock.so', 'libextract.so')) {
        $file = Get-Item -LiteralPath (Join-Path $jniRoot $name)
        $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        Write-Output "GHOSTLOCK_ARTIFACT=$name SIZE=$($file.Length) SHA256=$sha"
    }
} finally {
    if ($junctionPath -and (Test-Path -LiteralPath $junctionPath)) {
        $junction = Get-Item -LiteralPath $junctionPath -Force
        if (-not ($junction.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to remove non-junction path: $junctionPath"
        }
        [IO.Directory]::Delete($junctionPath)
    }
}
