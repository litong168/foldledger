# 诊断：置 DPI aware，移动鼠标并回读位置
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class CurApi {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@
[CurApi]::SetProcessDPIAware() | Out-Null
[CurApi]::SetCursorPos(1402, 1166) | Out-Null
Start-Sleep -Milliseconds 300
$p = New-Object CurApi+POINT
[CurApi]::GetCursorPos([ref]$p) | Out-Null
Write-Output ("cursor now at {0},{1}" -f $p.X, $p.Y)
