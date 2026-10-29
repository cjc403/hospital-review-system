param([switch]$NoBrowser, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.runtime'
$statePath = Join-Path $runtimePath 'processes.json'
$frontendUrl = 'http://127.0.0.1:18173'
$healthUrl = 'http://127.0.0.1:18080/api/health'
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null

function Test-Healthy([string]$Url) {
    try { $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2; return $r.StatusCode -eq 200 } catch { return $false }
}
function Wait-Healthy([string]$Url, [int]$Seconds) {
    $until = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $until) { if (Test-Healthy $Url) { return }; Start-Sleep -Milliseconds 500 }
    throw "服务未在 $Seconds 秒内就绪：$Url。日志目录：$runtimePath"
}
function Get-OwnerRecord($Process, [string]$Marker) {
    $Process.Refresh()
    return @{ id = $Process.Id; startedTicks = $Process.StartTime.ToUniversalTime().Ticks.ToString(); marker = $Marker }
}
function Get-ListenerRecord([int]$Port, [string]$Marker) {
    $processIds = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processIdValue in $processIds) {
        $info = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$processIdValue)
        if ($info.CommandLine -and $info.CommandLine.IndexOf($Marker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return Get-OwnerRecord (Get-Process -Id $processIdValue) $Marker
        }
    }
    throw "Port $Port is not owned by the expected demo process."
}

if (Test-Path -LiteralPath $statePath) {
    $saved = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $allOwned = $true
    foreach ($record in $saved.processes) {
        $owned = Get-Process -Id $record.id -ErrorAction SilentlyContinue
        if (!$owned -or $owned.StartTime.ToUniversalTime().Ticks.ToString() -ne $record.startedTicks) { $allOwned = $false }
    }
    if ($allOwned -and (Test-Healthy $healthUrl) -and (Test-Healthy $frontendUrl)) {
        Write-Host "演示系统已运行：$frontendUrl"
        if (!$NoBrowser) { Start-Process $frontendUrl }
        exit 0
    }
    & (Join-Path $PSScriptRoot 'stop.ps1') -Quiet
}

foreach ($port in @(18080, 18173)) {
    $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listeners) { throw "端口 $port 已被其他进程占用，未终止该进程。请先释放端口。" }
}
$javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
$nodeCommand = (Get-Command node.exe -ErrorAction Stop).Source
$backendRoot = Join-Path $projectRoot 'backend'
$frontendRoot = Join-Path $projectRoot 'frontend'
if (!$SkipBuild) {
    Push-Location $backendRoot
    try { & mvn.cmd -B -DskipTests package; if ($LASTEXITCODE -ne 0) { throw '后端构建失败' } } finally { Pop-Location }
    Push-Location $frontendRoot
    try {
        if (!(Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' } }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
}
$jar = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'target') -Filter '*.jar' | Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } | Select-Object -First 1
if (!$jar) { throw '未找到后端JAR，请去掉 -SkipBuild 后启动。' }
$dist = Join-Path $frontendRoot 'dist'
if (!(Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw '未找到前端构建结果，请去掉 -SkipBuild 后启动。' }
$records = @()
try {
    $backendProcess = Start-Process -FilePath $javaCommand -ArgumentList @('-jar', ('"' + $jar.FullName + '"'), '--server.address=127.0.0.1', '--server.port=18080') -WorkingDirectory $backendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'backend.out.log') -RedirectStandardError (Join-Path $runtimePath 'backend.err.log') -PassThru
    $records += Get-OwnerRecord $backendProcess $jar.FullName
    @{ processes = $records; url = $frontendUrl } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    Wait-Healthy $healthUrl 60
    # Oracle javapath may launch a child JVM. Track the actual listener, not its launcher.
    $records = @(Get-ListenerRecord 18080 $jar.FullName)
    @{ processes = $records; url = $frontendUrl } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $serverScript = Join-Path $PSScriptRoot 'serve-demo.mjs'
    $frontendProcess = Start-Process -FilePath $nodeCommand -ArgumentList @(('"' + $serverScript + '"'), '--root', ('"' + $dist + '"'), '--port', '18173') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'frontend.out.log') -RedirectStandardError (Join-Path $runtimePath 'frontend.err.log') -PassThru
    $records += Get-OwnerRecord $frontendProcess $serverScript
    @{ processes = $records; url = $frontendUrl } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    Wait-Healthy $frontendUrl 20
    $records = @($records[0], (Get-ListenerRecord 18173 $serverScript))
    @{ processes = $records; url = $frontendUrl } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    Write-Host "演示系统已启动：$frontendUrl"
    Write-Host "所有数据均为合成数据。停止请运行根目录 停止演示.cmd。日志：$runtimePath"
    if (!$NoBrowser) { Start-Process $frontendUrl }
} catch {
    & (Join-Path $PSScriptRoot 'stop.ps1') -Quiet
    throw
}
