# ============================================================
# SmartService-Agent 一键开发启动脚本 (Windows / PowerShell 5.1+)
#
# 功能: 环境自检 -> 自动拉起 Redis -> 检查 LM Studio ->
#       启动 platform(8080) -> 等待就绪 -> 打开浏览器
#
# 用法:
#   .\scripts\start-dev.ps1             一键启动
#   .\scripts\start-dev.ps1 -DryRun     只做环境自检，不启动任何服务
#   .\scripts\start-dev.ps1 -NoBrowser  启动服务但不自动打开浏览器
# ============================================================
param(
    [switch]$DryRun,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Continue'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# ---- 本机环境路径（按实际安装位置配置）----
$Maven       = 'C:\maven\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd'
$RedisServer = 'E:\bc\redis\redis-server.exe'
$PlatformDir = Join-Path $ProjectRoot 'java-agent-platform'
$HomeUrl     = 'http://localhost:8080/'

function Test-Port([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Write-Step([string]$Msg) { Write-Host "`n== $Msg" -ForegroundColor Cyan }

# ---------- 1/4 Java ----------
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  SmartService-Agent 一键启动" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Step "1/4 检查 Java (JDK 17)"
$javaOut = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -eq 0 -and $javaOut -match 'version "17') {
    Write-Host "  [OK] JDK 17 已安装" -ForegroundColor Green
} else {
    Write-Host "  [WARN] 未检测到 JDK 17，当前输出: $($javaOut.Trim())" -ForegroundColor Yellow
    if (-not $DryRun) {
        Write-Host "  请先安装 JDK 17 并配置 JAVA_HOME，再重试" -ForegroundColor Red
        exit 1
    }
}

# ---------- 2/4 Maven ----------
Write-Step "2/4 检查 Maven (全路径 3.9.9)"
if (Test-Path $Maven) {
    Write-Host "  [OK] $Maven" -ForegroundColor Green
} else {
    Write-Host "  [WARN] 未找到 $Maven" -ForegroundColor Red
    if (-not $DryRun) { exit 1 }
}

# ---------- 3/4 Redis ----------
Write-Step "3/4 检查 Redis (6379)"
if (Test-Port 6379) {
    Write-Host "  [OK] Redis 已在运行 (localhost:6379)" -ForegroundColor Green
} else {
    if (Test-Path $RedisServer) {
        if ($DryRun) {
            Write-Host "  [--] Redis 未运行，正式启动时会自动拉起" -ForegroundColor Yellow
        } else {
            Start-Process -FilePath $RedisServer -WorkingDirectory (Split-Path $RedisServer)
            Write-Host "  [OK] 已自动启动 Redis (独立窗口)" -ForegroundColor Green
            Start-Sleep -Seconds 2
        }
    } else {
        Write-Host "  [WARN] Redis 未运行，且找不到 $RedisServer" -ForegroundColor Yellow
        Write-Host "         请手动启动 Redis 后再运行本脚本" -ForegroundColor Yellow
    }
}

# ---------- 4/4 LM Studio ----------
Write-Step "4/4 检查 LM Studio (1234)"
if (Test-Port 1234) {
    try {
        $models = Invoke-RestMethod -Uri 'http://localhost:1234/v1/models' -TimeoutSec 5
        $names  = ($models.data | ForEach-Object { $_.id }) -join ', '
        Write-Host "  [OK] LM Studio 已连接，已加载模型: $names" -ForegroundColor Green
    } catch {
        Write-Host "  [WARN] 1234 端口通但 API 无响应，FAQ 可降级运行" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [WARN] LM Studio 未运行 (localhost:1234)" -ForegroundColor Yellow
    Write-Host "         服务仍可启动，但闲聊/复杂路由会降级走 FAQ" -ForegroundColor Yellow
    Write-Host "         建议: 打开 LM Studio 加载模型后启动 Local Server" -ForegroundColor Yellow
}

# ---------- 自检模式结束 ----------
if ($DryRun) {
    Write-Host "`n[DRY-RUN] 环境自检完成，未启动任何服务。正式运行: .\scripts\start-dev.ps1" -ForegroundColor Cyan
    exit 0
}

# ---------- 5/5 启动 platform ----------
Write-Step "5/5 启动 platform (java-agent-platform, 8080)"
if (Test-Port 8080) {
    Write-Host "  [WARN] 8080 已被占用，platform 可能已在运行，跳过启动" -ForegroundColor Yellow
} else {
    if (-not (Test-Path $PlatformDir)) {
        Write-Host "  [ERROR] 找不到 $PlatformDir" -ForegroundColor Red
        exit 1
    }
    Write-Host "  正在新窗口启动: mvn spring-boot:run (dev profile)" -ForegroundColor Green
    Start-Process cmd -ArgumentList "/k", "title SmartService-platform:8080 && `"$Maven`" spring-boot:run" -WorkingDirectory $PlatformDir

    Write-Host "  等待服务就绪 (最多 3 分钟)..." -ForegroundColor Gray
    $ready = $false
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 2
        if (Test-Port 8080) { $ready = $true; break }
    }
    if (-not $ready) {
        Write-Host "  [WARN] 等待超时，8080 未就绪，请查看 platform 窗口日志排查" -ForegroundColor Yellow
    }
}

# ---------- 6 打开浏览器 ----------
if (-not $NoBrowser -and (Test-Port 8080)) {
    Write-Host "  正在打开浏览器: $HomeUrl" -ForegroundColor Green
    Start-Process $HomeUrl
}

Write-Host "`n================ 启动完成，入口一览 ================" -ForegroundColor Cyan
Write-Host "  聊天端    : http://localhost:8080/" -ForegroundColor White
Write-Host "  管理后台  : http://localhost:8080/admin.html" -ForegroundColor White
Write-Host "  Swagger   : http://localhost:8080/swagger-ui/index.html" -ForegroundColor White
Write-Host "  健康探针  : http://localhost:9090/actuator/health" -ForegroundColor White
Write-Host "  停止服务  : 运行 scripts\stop-dev.ps1 或直接关闭 platform 窗口" -ForegroundColor White
Write-Host "=====================================================" -ForegroundColor Cyan
