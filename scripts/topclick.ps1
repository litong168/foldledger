param(
    [Parameter(Mandatory = $true)][int]$Hwnd,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y
)
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'TopClick.cs')
[TopClickApi]::SetProcessDPIAware() | Out-Null
[TopClickApi]::SetWindowPos([IntPtr]$Hwnd, [IntPtr](-1), 0, 0, 0, 0, 0x0043) | Out-Null
Start-Sleep -Milliseconds 300
[TopClickApi]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 150
[TopClickApi]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 60
[TopClickApi]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
Write-Output "topmost+clicked ($X, $Y)"
