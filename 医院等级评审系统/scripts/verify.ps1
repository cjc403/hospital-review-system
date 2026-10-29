$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $projectRoot 'backend')
try {
    & mvn.cmd -B package
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
& node.exe --test (Join-Path $PSScriptRoot 'serve-demo.test.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Demo server tests failed.' }
Write-Host 'All backend, frontend and demo-server checks passed.'
