# 只移动光标到窗口上（不点击）并滚动滚轮 —— hover 场景专用
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Delta
)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
[TopClickApi]::SetProcessDPIAware() | Out-Null
[TopClickApi]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 300
$uDelta = [uint32]([int64]$Delta -band 0xFFFFFFFF)
[TopClickApi]::mouse_event(0x0800, 0, 0, $uDelta, [UIntPtr]::Zero)
Write-Output "hover+wheel $Delta at ($X, $Y)"
