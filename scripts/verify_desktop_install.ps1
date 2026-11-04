# 安装态核验：安装包装完之后，把「该在的东西在不在、指向对不对」一次列清楚。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\verify_desktop_install.ps1
#
# 只读，不改任何东西。任何一项 FAIL 都会以非 0 退出码结束。
$ErrorActionPreference = 'Continue'
$app = '滴水记账'
$fail = 0

function Row([string]$item, [bool]$ok, [string]$detail) {
    $tag = if ($ok) { 'OK  ' } else { 'FAIL'; $script:fail++ }
    Write-Output ("[{0}] {1,-22} {2}" -f $tag, $item, $detail)
}

# ---- 1. 卸载项（应用和功能里能看到、能卸载）----
# jpackage 的 per-user 安装有时会把 ARP 写到 HKLM（msiexec 服务以 SYSTEM 身份写），
# 两个根都查一遍，别漏。
$arpRoots = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
)
$entry = $null
foreach ($r in $arpRoots) {
    foreach ($k in (Get-ChildItem $r -ErrorAction SilentlyContinue)) {
        $p = Get-ItemProperty $k.PSPath -ErrorAction SilentlyContinue
        if ($p.DisplayName -eq $app) { $entry = $p; break }
    }
    if ($entry) { break }
}
Row '卸载项(ARP)' ($null -ne $entry) $(
    if ($entry) { "v$($entry.DisplayVersion)  $($entry.UninstallString)" } else { '注册表里找不到' })

$installDir = if ($entry -and $entry.InstallLocation) { $entry.InstallLocation.TrimEnd('\') } else { $null }
Row '安装目录' ($installDir -and (Test-Path $installDir)) "$installDir"

# ---- 2. 程序文件 ----
$exe = if ($installDir) { Join-Path $installDir "$app.exe" } else { $null }
$exeOk = $exe -and (Test-Path $exe)
$ver = if ($exeOk) { (Get-Item $exe).VersionInfo.ProductVersion } else { '' }
Row '主程序' $exeOk ("$exe" + $(if ($ver) { "  (ProductVersion $ver)" } else { '' }))

$cfg = if ($installDir) { Join-Path $installDir "app\$app.cfg" } else { $null }
Row '运行配置' ($cfg -and (Test-Path $cfg)) "$cfg"
# jlink 裁剪过的运行时不带 java.exe（启动器直接加载 jvm.dll），看 jvm.dll 才准
$runtime = if ($installDir) { Join-Path $installDir 'runtime\bin\server\jvm.dll' } else { $null }
Row '内置运行时' ($runtime -and (Test-Path $runtime)) "$runtime"

# jar 里的版本串（jar 是压缩包，要解压后找；防止装到旧包）
$jar = if ($installDir) { Join-Path $installDir 'app\desktop-windows-x64-1.0.0-release.jar' } else { $null }
if ($jar -and (Test-Path $jar)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $found = @()
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.EndsWith('.class')) { continue }
            $st = $e.Open()
            $ms = New-Object IO.MemoryStream
            try { $st.CopyTo($ms) } finally { $st.Dispose() }
            [regex]::Matches([Text.Encoding]::UTF8.GetString($ms.ToArray()), '16\.[0-9]+\.[0-9]+') |
                ForEach-Object { if ($found -notcontains $_.Value) { $found += $_.Value } }
            if ($found.Count -ge 3) { break }
        }
    } finally { $zip.Dispose() }
    Row 'jar 版本串' ($found.Count -gt 0) (($found | Select-Object -First 5) -join ', ')
} else {
    Row 'jar 版本串' $false '找不到 uber jar'
}

# ---- 3. 快捷方式（桌面 + 开始菜单；开始菜单可能在以菜单组命名的子目录里）----
$sh = New-Object -ComObject WScript.Shell
$lnkFiles = @()
$deskLnk = Join-Path ([Environment]::GetFolderPath('Desktop')) "$app.lnk"
if (Test-Path $deskLnk) { $lnkFiles += $deskLnk }
Get-ChildItem ([Environment]::GetFolderPath('Programs')) -Recurse -Filter "$app.lnk" -ErrorAction SilentlyContinue |
    ForEach-Object { $lnkFiles += $_.FullName }
if ($lnkFiles.Count -eq 0) {
    Row '快捷方式' $false '桌面与开始菜单都没找到'
} else {
    foreach ($l in $lnkFiles) {
        $t = $sh.CreateShortcut($l).TargetPath
        Row '快捷方式' ($t -eq $exe) "$l -> $t"
    }
}

# ---- 4. 用户数据（安装/卸载都不该动它）----
$data = Join-Path $env:USERPROFILE '.foldledger'
$db = Join-Path $data 'foldledger.db'
$dbOk = Test-Path $db
Row '用户数据' $dbOk $(if ($dbOk) { "foldledger.db $([math]::Round((Get-Item $db).Length/1KB)) KB  改于 $((Get-Item $db).LastWriteTime)" } else { "$db 不存在" })

# ---- 5. 开机自启注册表（应用运行时写，指向当前 exe）----
$run = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name $app -ErrorAction SilentlyContinue).$app
if ($run) {
    $runOk = $exe -and $run.Contains($exe)
    Row '开机自启' $runOk $run
} else {
    Row '开机自启' $true '未开启（未写注册表）'
}

# ---- 6. 进程 ----
$proc = Get-Process -Name $app -ErrorAction SilentlyContinue
Row '进程' $true $(if ($proc) { "运行中 PID " + (($proc | Select-Object -First 1).Id) } else { '未运行' })

Write-Output ''
if ($fail -gt 0) { Write-Output "== $fail 项未通过 =="; exit 1 } else { Write-Output '== 全部通过 ==' }
