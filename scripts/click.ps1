# 模拟鼠标点击物理坐标：先把目标窗口置前（防遮挡），再 SetCursorPos + mouse_event
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [int]$Hwnd = 0
)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class MouseApi2 {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
}
"@
# 关键：不置 DPI aware 时 SetCursorPos 按虚拟坐标解释，150% 缩放下会点偏（物理*2/3）
[MouseApi2]::SetProcessDPIAware() | Out-Null
if ($Hwnd -ne 0) {
    [MouseApi2]::BringWindowToTop([IntPtr]$Hwnd) | Out-Null
    [MouseApi2]::SetForegroundWindow([IntPtr]$Hwnd) | Out-Null
    Start-Sleep -Milliseconds 300
}
[MouseApi2]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 150
[MouseApi2]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)   # LEFTDOWN
[MouseApi2]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)   # LEFTUP
Write-Output "clicked ($X, $Y)"
