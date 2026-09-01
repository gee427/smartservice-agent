# ============================================================
# SmartService-Agent 一键停止脚本 (Windows / PowerShell 5.1+)
#
# 用法:
#   .\scripts\stop-dev.ps1            只停 platform (8080)
#   .\scripts\stop-dev.ps1 -All       连带停止 Redis (6379)
# ============================================================
param([switch]$All)

$ErrorActionPreference = 'Continue'

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

function Stop-PortProcess([int]$Port, [string]$Label) {
    Write-Host "== 停止 $Label (端口 $Port) ==" -ForegroundColor Cyan
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        Write-Host "  [--] 端口 $Port 无监听进程，$Label 未在运行" -ForegroundColor Gray
        return
    }
    $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($procId in $pids) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "  终止进程: $($proc.ProcessName) (PID $procId)" -ForegroundColor Yellow
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Milliseconds 500
    if (Test-Port $Port) {
        Write-Host "  [WARN] 端口仍被占用，请手动检查" -ForegroundColor Yellow
    } else {
        Write-Host "  [OK] $Label 已停止" -ForegroundColor Green
    }
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  SmartService-Agent 一键停止" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Stop-PortProcess 8080 'platform'

if ($All) {
    Stop-PortProcess 6379 'Redis'
}

Write-Host "`n[完成] 如需同时停止 Redis: .\scripts\stop-dev.ps1 -All" -ForegroundColor Cyan
