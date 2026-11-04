# 在指定位置滚动滚轮（向下正数、向上负数）
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Delta  # 例如 -600 = 向下滚 600
)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
[TopClickApi]::SetProcessDPIAware() | Out-Null
[TopClickApi]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 120
# MOUSEEVENTF_WHEEL = 0x0800，delta 以 WHEEL_DELTA(120) 为单位的倍数；
# 负数以两补码转 uint32（PowerShell 不能直接把负数转 uint）
$uDelta = [uint32]([int64]$Delta -band 0xFFFFFFFF)
[TopClickApi]::mouse_event(0x0800, 0, 0, $uDelta, [UIntPtr]::Zero)
Write-Output "wheel $Delta at ($X, $Y)"
