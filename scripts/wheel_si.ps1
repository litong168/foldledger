# SendInput 滚轮（delta 直接放 mouseData，不依赖 mouse_event 的编码）
param(
    [Parameter(Mandatory = $true)][int]$Delta
)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class SIW {
    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] ins, int size);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public MOUSEINPUT mi; }
}
"@
[SIW]::SetProcessDPIAware() | Out-Null
$input = New-Object SIW+INPUT
$input.type = 0   # INPUT_MOUSE
$input.mi.dwFlags = 0x0800   # MOUSEEVENTF_WHEEL
$input.mi.mouseData = [BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$Delta), 0)
$ret = [SIW]::SendInput(1, @($input), [Runtime.InteropServices.Marshal]::SizeOf([type][SIW+INPUT]))
Write-Output "SENDINPUT_WHEEL ret=$ret delta=$Delta"
