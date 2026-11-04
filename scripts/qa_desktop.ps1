# 桌面 QA 截图脚本（复用 M2 沉淀的方法）：
# SetProcessDPIAware + GetWindowRect（物理尺寸）+ PrintWindow(2) → PNG
# 用法: powershell -File qa_desktop.ps1 -ProcId 37640 -Out "qa.png"
param(
    [Parameter(Mandatory = $true)][int]$ProcId,
    [Parameter(Mandatory = $true)][string]$Out
)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WinApi {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
"@
[WinApi]::SetProcessDPIAware() | Out-Null
$proc = Get-Process -Id $ProcId -ErrorAction Stop
$hwnd = $proc.MainWindowHandle
if ($hwnd -eq 0) { throw "no main window for ProcId $ProcId" }
$rect = New-Object WinApi+RECT
[WinApi]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$w = $rect.right - $rect.left
$h = $rect.bottom - $rect.top
Write-Output "window ${w}x${h} at ($($rect.left),$($rect.top))"
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
[WinApi]::PrintWindow($hwnd, $hdc, 2) | Out-Null
$g.ReleaseHdc($hdc)
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "saved $Out"
