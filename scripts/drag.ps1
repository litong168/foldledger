# 鼠标拖拽：按住从 (X1,Y1) 拖到 (X2,Y2)（向上拖 = 列表向下滚）
param(
    [Parameter(Mandatory = $true)][int]$X1,
    [Parameter(Mandatory = $true)][int]$Y1,
    [Parameter(Mandatory = $true)][int]$X2,
    [Parameter(Mandatory = $true)][int]$Y2
)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
[TopClickApi]::SetProcessDPIAware() | Out-Null
[TopClickApi]::SetCursorPos($X1, $Y1) | Out-Null
Start-Sleep -Milliseconds 200
[TopClickApi]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)   # LEFTDOWN
Start-Sleep -Milliseconds 150
# 分步移动，让 Compose 收到连续的 drag 事件
$steps = 10
for ($i = 1; $i -le $steps; $i++) {
    $px = $X1 + [int](($X2 - $X1) * $i / $steps)
    $py = $Y1 + [int](($Y2 - $Y1) * $i / $steps)
    [TopClickApi]::SetCursorPos($px, $py) | Out-Null
    Start-Sleep -Milliseconds 30
}
Start-Sleep -Milliseconds 120
[TopClickApi]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)   # LEFTUP
Write-Output "dragged ($X1,$Y1)->($X2,$Y2)"
