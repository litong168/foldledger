# 通过 PostMessage 向指定窗口注入鼠标点击（不依赖前台焦点）
param(
    [Parameter(Mandatory = $true)][int]$Hwnd,
    [Parameter(Mandatory = $true)][int]$ScreenX,
    [Parameter(Mandatory = $true)][int]$ScreenY
)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class PostClickApi {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool ScreenToClient(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@
[PostClickApi]::SetProcessDPIAware() | Out-Null
$p = New-Object PostClickApi+POINT
$p.X = $ScreenX
$p.Y = $ScreenY
[PostClickApi]::ScreenToClient([IntPtr]$Hwnd, [ref]$p) | Out-Null
$x = $p.X; $y = $p.Y
$lParam = (($y -band 0xFFFF) -shl 16) -bor ($x -band 0xFFFF)
[PostClickApi]::PostMessage([IntPtr]$Hwnd, 0x0201, [IntPtr]1, [IntPtr]$lParam) | Out-Null  # WM_LBUTTONDOWN, MK_LBUTTON
[PostClickApi]::PostMessage([IntPtr]$Hwnd, 0x0202, [IntPtr]0, [IntPtr]$lParam) | Out-Null  # WM_LBUTTONUP
Write-Output ("posted click at client ({0},{1})" -f $x, $y)
