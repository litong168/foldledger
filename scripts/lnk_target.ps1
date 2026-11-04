# 读出桌面（以及公共桌面）上指向滴水记账的快捷方式指向哪里，用来确认"用户到底双击的是哪个 exe"。
param([string]$Pattern = "*滴水*")
$sh = New-Object -ComObject WScript.Shell
$dirs = @("$env:USERPROFILE\Desktop", "$env:PUBLIC\Desktop") | Where-Object { Test-Path $_ }
$found = $false
foreach ($d in $dirs) {
    Get-ChildItem $d -Filter "*.lnk" -ErrorAction SilentlyContinue | Where-Object { $_.Name -like $Pattern } | ForEach-Object {
        $lnk = $sh.CreateShortcut($_.FullName)
        $exists = Test-Path $lnk.TargetPath
        Write-Output ("{0} -> {1}  (存在={2})" -f $_.FullName, $lnk.TargetPath, $exists)
        Write-Output ("    图标: '{0}'  索引 {1}" -f $lnk.IconLocation, $lnk.IconLocation)
        if ($exists) {
            $f = Get-Item $lnk.TargetPath
            Write-Output ("    目标: {0} KB, 修改 {1}" -f [math]::Round($f.Length / 1KB), $f.LastWriteTime)
        }
        $found = $true
    }
}
if (-not $found) { Write-Output "桌面上没找到匹配 '$Pattern' 的快捷方式" }
