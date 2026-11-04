# 一体化：激活指定进程主窗口 + 立即在屏幕坐标点击（缩短红果短剧抢前台的窗口期）
param(
    [Parameter(Mandatory = $true)][string]$ProcName,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y
)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ActClick {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, System.UIntPtr e);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
}
"@
[ActClick]::SetProcessDPIAware() | Out-Null
$proc = Get-Process $ProcName -ErrorAction Stop |
    Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$hwnd = $proc.MainWindowHandle
[ActClick]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
$fg = [ActClick]::GetForegroundWindow()
$fgThread = [ActClick]::GetWindowThreadProcessId($fg, [ref]0)
$myThread = [ActClick]::GetCurrentThreadId()
[ActClick]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[ActClick]::SetForegroundWindow($hwnd) | Out-Null
[ActClick]::BringWindowToTop($hwnd) | Out-Null
[ActClick]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
[ActClick]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 120
[ActClick]::mouse_event(0x0002, 0, 0, 0, [System.UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[ActClick]::mouse_event(0x0004, 0, 0, 0, [System.UIntPtr]::Zero)
Write-Output "ACTCLICK $X,$Y -> $hwnd"
