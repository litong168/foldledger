# 一体化：激活指定进程主窗口 + 立即在坐标处 SendInput 滚轮（缩短被其他窗口抢 Z 序的窗口期）
param(
    [Parameter(Mandatory = $true)][string]$ProcName,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$Delta
)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ActWheel {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] ins, int size);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public MOUSEINPUT mi; }
}
"@
[ActWheel]::SetProcessDPIAware() | Out-Null
$proc = Get-Process $ProcName -ErrorAction Stop |
    Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$hwnd = $proc.MainWindowHandle
[ActWheel]::ShowWindow($hwnd, 9) | Out-Null
$fg = [ActWheel]::GetForegroundWindow()
$fgThread = [ActWheel]::GetWindowThreadProcessId($fg, [ref]0)
$myThread = [ActWheel]::GetCurrentThreadId()
[ActWheel]::AttachThreadInput($myThread, $fgThread, $true) | Out-Null
[ActWheel]::SetForegroundWindow($hwnd) | Out-Null
[ActWheel]::BringWindowToTop($hwnd) | Out-Null
[ActWheel]::AttachThreadInput($myThread, $fgThread, $false) | Out-Null
[ActWheel]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 120
$input = New-Object ActWheel+INPUT
$input.type = 0
$input.mi.dwFlags = 0x0800
$input.mi.mouseData = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$Delta), 0)
[ActWheel]::SendInput(1, @($input), [Runtime.InteropServices.Marshal]::SizeOf([type][ActWheel+INPUT])) | Out-Null
Write-Output "ACTWHEEL ($X,$Y) delta=$Delta -> $hwnd"
