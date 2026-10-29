param([switch]$NoBrowser, [switch]$SkipBuild)
& (Join-Path $PSScriptRoot 'start-demo.ps1') -NoBrowser:$NoBrowser -SkipBuild:$SkipBuild
