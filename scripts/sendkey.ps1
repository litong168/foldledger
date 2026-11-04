# SendInput 发送按键（按住-释放）到前台窗口
param([Parameter(Mandatory = $true)][int]$Vk)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class KeyApi {
    [DllImport("user32.dll", SetLastError = true)] public static extern uint SendInput(uint n, INPUT[] ins, int size);
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
}
"@
$down = New-Object KeyApi+INPUT
$down.type = 1   # INPUT_KEYBOARD
$down.ki.wVk = [uint16]$Vk
$up = New-Object KeyApi+INPUT
$up.type = 1
$up.ki.wVk = [uint16]$Vk
$up.ki.dwFlags = 2   # KEYEVENTF_KEYUP
[KeyApi]::SendInput(2, @($down, $up), [System.Runtime.InteropServices.Marshal]::SizeOf([type][KeyApi+INPUT])) | Out-Null
Write-Output "sent key VK=$Vk"
