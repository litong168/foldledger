# 直接向窗口句柄发鼠标点击（PostMessage，不需要窗口在前台）
# 用法: -Hwnd <句柄> -ScreenX <全屏x> -ScreenY <全屏y> -WinLeft <窗口左> -WinTop <窗口顶>
param(
    [Parameter(Mandatory = $true)][int]$Hwnd,
    [Parameter(Mandatory = $true)][int]$ScreenX,
    [Parameter(Mandatory = $true)][int]$ScreenY,
    [Parameter(Mandatory = $true)][int]$WinLeft,
    [Parameter(Mandatory = $true)][int]$WinTop
)
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class CH3{[DllImport("user32.dll")]public static extern bool PostMessage(IntPtr h,int m,IntPtr w,IntPtr l);}'
$cx = $ScreenX - $WinLeft
$cy = $ScreenY - $WinTop
$lp = (($cy -band 0xFFFF) -shl 16) -bor ($cx -band 0xFFFF)
[CH3]::PostMessage($Hwnd, 0x201, [IntPtr]1, [IntPtr]$lp) | Out-Null
Start-Sleep -Milliseconds 80
[CH3]::PostMessage($Hwnd, 0x202, [IntPtr]0, [IntPtr]$lp) | Out-Null
Write-Output "CLIENT=$cx,$cy"
