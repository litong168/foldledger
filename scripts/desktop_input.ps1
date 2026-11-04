# 桌面 UI 自动化辅助：模拟鼠标点击 / 滚轮 / 键盘按键（屏幕坐标）
param(
    [int]$X = -1,
    [int]$Y = -1,
    [string]$Key = '',
    [int]$Wheel = 0
)
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class DIn {[DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);[DllImport("user32.dll")]public static extern void mouse_event(uint f,uint dx,uint dy,uint d,System.UIntPtr e);[DllImport("user32.dll")]public static extern bool SetProcessDPIAware();}'
# 关键：声明进程 DPI aware，否则 SetCursorPos 按逻辑像素换算，物理坐标会偏移 1.5x 落空
[DIn]::SetProcessDPIAware() | Out-Null
# 滚轮/定位模式不点击：X/Y 仅用于把光标放到目标位置
if ($X -ge 0 -and $Y -ge 0 -and $Wheel -eq 0) {
    [DIn]::SetCursorPos($X, $Y) | Out-Null
    Start-Sleep -Milliseconds 150
    [DIn]::mouse_event(0x0002, 0, 0, 0, [System.UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [DIn]::mouse_event(0x0004, 0, 0, 0, [System.UIntPtr]::Zero)
    Write-Output "CLICK $X,$Y"
}
if ($Wheel -ne 0) {
    if ($X -ge 0 -and $Y -ge 0) { [DIn]::SetCursorPos($X, $Y) | Out-Null }
    Start-Sleep -Milliseconds 100
    # 滚轮 delta 必须放在 32 位的高 16 位（系统按有符号读高字）；直接传 -120 会被读成 -1 忽略
    $wheelData = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]($Wheel -shl 16)), 0)
    [DIn]::mouse_event(0x0800, 0, 0, $wheelData, [System.UIntPtr]::Zero)
    Write-Output "WHEEL $Wheel"
}
if ($Key) {
    $ws = New-Object -ComObject WScript.Shell
    $ws.SendKeys($Key)
    Write-Output "KEY $Key"
}
