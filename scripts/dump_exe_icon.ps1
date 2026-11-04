# 从 exe 里抽出关联图标并放大保存，用于核对 jpackage 是否真的换了图标。
# 用法：powershell -ExecutionPolicy Bypass -File scripts\dump_exe_icon.ps1 -Exe <exe> -Out <png>
param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$Out
)
Add-Type -AssemblyName System.Drawing
$ico = [System.Drawing.Icon]::ExtractAssociatedIcon($Exe)
if ($null -eq $ico) { Write-Output "NO_ICON"; exit 1 }
$src = $ico.ToBitmap()
$size = 256
$dst = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.Clear([System.Drawing.Color]::FromArgb(0, 0, 0, 0))
$g.DrawImage($src, 0, 0, $size, $size)
$g.Dispose()
$dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
Write-Output ("EXE_ICON {0}x{1} -> {2}" -f $src.Width, $src.Height, $Out)
