# 激活指定进程的主窗口到前台（AttachThreadInput 绕 Windows 前台锁）
param([Parameter(Mandatory = $true)][string]$ProcName)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WinAct {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
}
"@
$proc = Get-Process $ProcName -ErrorAction Stop |
    Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$hwnd = $proc.MainWindowHandle
[WinAct]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
$fg = [WinAct]::GetForegroundWindow()
$fgThread = [WinAct]::GetWindowThreadProcessId($fg, [ref]0)
$myThread = [WinAct]::GetCurrentThreadId()
[WinAct]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[WinAct]::SetForegroundWindow($hwnd) | Out-Null
[WinAct]::BringWindowToTop($hwnd) | Out-Null
[WinAct]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
Start-Sleep -Milliseconds 800
$fg2 = [WinAct]::GetForegroundWindow()
Write-Output "HWND=$hwnd FOREGROUND=$fg2"
