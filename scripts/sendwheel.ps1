# SendInput 注入滚轮事件（delta 为 WHEEL_DELTA 倍数，向下为正）
param([Parameter(Mandatory = $true)][int]$Ticks)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WheelApi {
    [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint n, INPUT[] ins, int size);
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public MOUSEINPUT mi; }
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
}
"@
$inp = New-Object WheelApi+INPUT
$inp.type = 0                      # INPUT_MOUSE
# 负数以两补码转 uint32（-15 → 0xFFFFFFF1）
$inp.mi.mouseData = [uint32]([int64]$Ticks -band 0xFFFFFFFF)
$inp.mi.dwFlags = 0x0800           # MOUSEEVENTF_WHEEL
[WheelApi]::SendInput(1, @($inp), [System.Runtime.InteropServices.Marshal]::SizeOf([type][WheelApi+INPUT])) | Out-Null
Write-Output "sendinput wheel ticks=$Ticks"
