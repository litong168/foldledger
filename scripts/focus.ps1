# 强制把目标窗口设为前台（AttachThreadInput 绕过前台锁）
param([Parameter(Mandatory = $true)][int]$Hwnd)
$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FgForce {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
}
"@
$fg = [FgForce]::GetForegroundWindow()
$fgPid = 0
$fgThread = [FgForce]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$myThread = [FgForce]::GetCurrentThreadId()
[FgForce]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[FgForce]::BringWindowToTop([IntPtr]$Hwnd) | Out-Null
[FgForce]::SetForegroundWindow([IntPtr]$Hwnd) | Out-Null
[FgForce]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
Start-Sleep -Milliseconds 400
$check = [FgForce]::GetForegroundWindow()
Write-Output ("foreground now: {0} (target {1})" -f $check, $Hwnd)
