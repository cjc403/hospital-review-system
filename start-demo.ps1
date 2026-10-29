param([switch]$NoBrowser, [switch]$SkipBuild)
& (Join-Path $PSScriptRoot '医院等级评审系统\scripts\start.ps1') -NoBrowser:$NoBrowser -SkipBuild:$SkipBuild
