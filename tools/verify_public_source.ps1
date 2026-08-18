[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path -LiteralPath $Root).Path

$forbiddenNames = @(
    'server.properties',
    'keystore.properties',
    'local.properties',
    'google-services.json'
)
$forbiddenExtensions = @('.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab')
$forbiddenDirectories = @('.gradle', '.kotlin', '.cxx', '.externalNativeBuild', 'target', 'out', 'build', '.codex_fixture', '.codex_transaction')

$allFiles = Get-ChildItem -LiteralPath $Root -Recurse -Force -File
$allDirectories = Get-ChildItem -LiteralPath $Root -Recurse -Force -Directory
$badFiles = $allFiles | Where-Object {
    $_.Name -in $forbiddenNames -or $_.Extension.ToLowerInvariant() -in $forbiddenExtensions
}
$badDirectories = $allDirectories | Where-Object { $_.Name -in $forbiddenDirectories }

$example = Join-Path $Root 'server.properties.example'
if (-not (Test-Path -LiteralPath $example -PathType Leaf)) {
    throw 'server.properties.example is missing'
}
$nonEmptyExampleValues = Get-Content -LiteralPath $example | Where-Object {
    $_ -match '^\s*[^#!][^=]*=\s*\S+'
}

$announcement = Join-Path $Root 'assistant\src\main\java\roro\stellar\yuehong\ui\AnnouncementDialog.kt'
$hardcodedUpdateUrl = Select-String -LiteralPath $announcement -Pattern 'UPDATE_URL\s*=\s*"https?://' -Quiet

$textExtensions = @('.kt', '.java', '.gradle', '.properties', '.xml', '.json', '.md', '.txt', '.sh', '.ps1', '.toml', '.rs', '.c', '.h', '.yml', '.yaml')
$textFiles = $allFiles | Where-Object { $_.Extension.ToLowerInvariant() -in $textExtensions }
$privateMaterialHits = $textFiles | Select-String -Pattern @(
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '^\s*(storePassword|keyPassword)\s*=\s*\S+'
)

$requiredLicenseFiles = @(
    'LICENSE',
    'LICENSES\MPL-2.0.txt',
    'LICENSES\Apache-2.0.txt',
    'LICENSES\GhostLock-Apache-2.0.txt',
    'NOTICE.md'
)
$missingLicenses = $requiredLicenseFiles | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $Root $_) -PathType Leaf)
}

Write-Output "ROOT=$Root"
Write-Output "FILES_SCANNED=$($allFiles.Count)"
Write-Output "FORBIDDEN_FILES=$($badFiles.Count)"
Write-Output "FORBIDDEN_DIRECTORIES=$($badDirectories.Count)"
Write-Output "NONEMPTY_EXAMPLE_VALUES=$($nonEmptyExampleValues.Count)"
Write-Output "HARDCODED_UPDATE_URL=$hardcodedUpdateUrl"
Write-Output "PRIVATE_MATERIAL_HITS=$($privateMaterialHits.Count)"
Write-Output "MISSING_LICENSE_FILES=$($missingLicenses.Count)"

if ($badFiles.Count -gt 0 -or
    $badDirectories.Count -gt 0 -or
    $nonEmptyExampleValues.Count -gt 0 -or
    $hardcodedUpdateUrl -or
    $privateMaterialHits.Count -gt 0 -or
    $missingLicenses.Count -gt 0) {
    Write-Output 'PUBLIC_SOURCE_RESULT=FAIL'
    exit 1
}

Write-Output 'PUBLIC_SOURCE_RESULT=PASS'
