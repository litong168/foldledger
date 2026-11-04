# 诊断：当前前台窗口 + 目标窗口信息
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class FgApi {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int l, t, r, b; }
}
"@
$fg = [FgApi]::GetForegroundWindow()
$sb = New-Object System.Text.StringBuilder 256
[FgApi]::GetWindowText($fg, $sb, 256) | Out-Null
Write-Output ("foreground: hwnd={0} title={1}" -f $fg, $sb.ToString())
