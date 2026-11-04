# 打包桌面端安装包（Windows exe 安装器）
#
# 产出：dist\滴水记账-v<版本>-win-x64-setup.exe
# 说明：v16.2.9 起桌面端只以安装包分发，不再产出绿色免安装 zip。
#
# 依赖：JDK 17.0.2 + WiX Toolset 3.14（jpackage 生成 MSI 需要 candle/light）。
#       WiX 放在 C:\Users\16039\.workbuddy\tools\wix（见交接文档 §6）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\build_installer.ps1            # 全量（含 uber jar）
#   powershell -ExecutionPolicy Bypass -File scripts\build_installer.ps1 -SkipJar   # 只用现有 jar
param(
    [switch]$SkipJar
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = 'C:\Users\16039\.workbuddy\binaries\jdk\17.0.2'
$wixDir = 'C:\Users\16039\.workbuddy\tools\wix'
if (-not (Test-Path "$wixDir\candle.exe") -or -not (Test-Path "$wixDir\light.exe")) {
    throw "找不到 WiX 3.x：$wixDir\candle.exe。jpackage 生成 exe/msi 必须有 WiX。"
}
$env:PATH = "$wixDir;$env:PATH"

# ---- 版本号：以 AppInfo.kt 为唯一来源 ----
$appInfo = Get-Content 'common\src\commonMain\kotlin\com\foldledger\AppInfo.kt' -Raw -Encoding UTF8
$m = [regex]::Match($appInfo, 'APP_VERSION_NAME\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $m.Success) { throw 'AppInfo.kt 里没读到 APP_VERSION_NAME' }
$ver = $m.Groups[1].Value
$code = [regex]::Match($appInfo, 'APP_VERSION_CODE\s*=\s*(\d+)').Groups[1].Value
Write-Host "== 目标版本 $ver ($code) =="

# ---- 1. uber jar ----
$jarDir = 'desktop\build\compose\jars'
$jar = Join-Path $jarDir 'desktop-windows-x64-1.0.0-release.jar'
if (-not $SkipJar) {
    Write-Host '== 打 uber jar =='
    Remove-Item "$jarDir\*.jar" -Force -ErrorAction SilentlyContinue
    & '.\gradlew.bat' :desktop:packageReleaseUberJarForCurrentOS --no-daemon 2>&1 |
        Select-String -Pattern '^e: |FAILED|BUILD ' | Select-Object -Last 6
    if (-not (Test-Path $jar)) { throw "uber jar 没生成：$jar" }
}

# ---- 2. 校验 jar 里确实是这一版（防止忘记重编译） ----
# 注意：jar 是压缩包，不能直接扫原始字节（版本串会被 deflate 压掉），必须解压后找。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$hit = $false
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $jar))
try {
    foreach ($e in $zip.Entries) {
        if (-not $e.FullName.EndsWith('.class')) { continue }
        $st = $e.Open()
        $ms = New-Object IO.MemoryStream
        try { $st.CopyTo($ms) } finally { $st.Dispose() }
        if ([Text.Encoding]::UTF8.GetString($ms.ToArray()).Contains($ver)) { $hit = $true; break }
    }
} finally { $zip.Dispose() }
if (-not $hit) { throw "jar 里找不到版本串 $ver，先重新编译（去掉 -SkipJar）" }
Write-Host "== jar 校验通过：含 $ver =="

# ---- 3. jpackage 生成安装包 ----
if (-not (Test-Path 'dist')) { New-Item -ItemType Directory 'dist' | Out-Null }
$out = 'dist\_installer'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory $out | Out-Null

Write-Host '== jpackage --type exe（candle/light 编译 MSI，约 1~3 分钟）=='
& "$env:JAVA_HOME\bin\jpackage.exe" --type exe `
    --input $jarDir `
    --main-jar 'desktop-windows-x64-1.0.0-release.jar' `
    --main-class 'com.foldledger.desktop.MainKt' `
    --name '滴水记账' `
    --app-version $ver `
    --icon 'scripts\app-icon.ico' `
    --vendor '滴水记账' `
    --description '分账本 + 场景化记账的桌面版' `
    --win-per-user-install `
    --win-dir-chooser `
    --win-menu `
    --win-menu-group '滴水记账' `
    --win-shortcut `
    --win-upgrade-uuid 'FA0CF5BB-63FE-4A65-BF65-5A3A3EEF0828' `
    --resource-dir 'scripts\jpackage' `
    --dest $out 2>&1 | Select-Object -Last 20
if ($LASTEXITCODE -ne 0) { throw "jpackage 失败，退出码 $LASTEXITCODE" }

$built = Join-Path $out "滴水记账-$ver.exe"
if (-not (Test-Path $built)) { throw "没找到产物：$built" }
$target = "dist\滴水记账-v$ver-win-x64-setup.exe"
Move-Item $built $target -Force
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue

$f = Get-Item $target
Write-Host ("== 完成：{0}  {1} MB ==" -f $f.Name, [math]::Round($f.Length / 1MB, 1))
Write-Host '   安装：双击即可（默认装到 %LOCALAPPDATA%\滴水记账，无需管理员）'
Write-Host '   静默：& "<setup.exe>" /quiet'
