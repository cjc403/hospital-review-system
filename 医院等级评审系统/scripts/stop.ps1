param([switch]$Quiet)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $projectRoot '.runtime\processes.json'
if (!(Test-Path -LiteralPath $statePath)) { if (!$Quiet) { Write-Host '未找到本演示的运行记录。' }; exit 0 }
$saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
function Stop-MatchingTree([int]$ProcessIdValue, [string]$Marker) {
    $children = Get-CimInstance Win32_Process -Filter ('ParentProcessId=' + $ProcessIdValue) -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        if ($child.CommandLine -and $child.CommandLine.IndexOf($Marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Stop-MatchingTree ([int]$child.ProcessId) $Marker
        }
    }
    $current = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $ProcessIdValue) -ErrorAction SilentlyContinue
    if ($current -and $current.CommandLine -and $current.CommandLine.IndexOf($Marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        Stop-Process -Id $ProcessIdValue -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $ProcessIdValue -Timeout 10 -ErrorAction SilentlyContinue
    }
}
foreach ($record in $saved.processes) {
    $owned = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if (!$owned) { continue }
    $sameStart = $owned.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.startedTicks
    $processInfo = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$record.id) -ErrorAction SilentlyContinue
    $sameCommand = $processInfo -and $processInfo.CommandLine -and $processInfo.CommandLine.IndexOf([string]$record.marker, [StringComparison]::OrdinalIgnoreCase) -ge 0
    if ($sameStart -and $sameCommand) {
        Stop-MatchingTree ([int]$record.id) ([string]$record.marker)
    }
    elseif (!$Quiet) { Write-Warning "PID $($record.id) 不再属于本演示，已跳过。" }
}
Remove-Item -LiteralPath $statePath -Force
if (!$Quiet) { Write-Host '已停止本演示启动的服务，数据保留。' }
