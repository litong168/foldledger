# 打出当前前台窗口的进程与标题，用来确认注入的键鼠到底落在哪个窗口。
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class FG {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
}
"@
$h = [FG]::GetForegroundWindow()
$sb = New-Object System.Text.StringBuilder 256
[FG]::GetWindowText($h, $sb, 256) | Out-Null
$pid2 = 0
[FG]::GetWindowThreadProcessId($h, [ref]$pid2) | Out-Null
$p = Get-Process -Id $pid2 -ErrorAction SilentlyContinue
Write-Output ("前台窗口: '{0}'  进程: {1} (PID {2})" -f $sb.ToString(), $p.ProcessName, $pid2)
