<#
  一键出包 + 生成更新清单。

  用法：
    .\tools\release.ps1 -MainVersion 0.25.1 -MainCode 33 -A5Version 0.22.5 -A5Code 34
    .\tools\release.ps1 -MainVersion 0.25.1 -MainCode 33 -SkipLegacy      # 不动安卓5支线
    .\tools\release.ps1 -MainVersion 0.25.1 -MainCode 33 -SkipBuild -OutDir D:\tmp\x

  产出（默认 releases\v<版本>\）：
    4 个 APK + update.json + SHA256SUMS.txt
  之后到 CNB 建同名 tag 的 Release，把这 6 个文件作为附件上传即可。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MainVersion,
    [Parameter(Mandatory = $true)][int]$MainCode,
    [string]$A5Version,
    [int]$A5Code,
    [string]$Notes = "问题修复与稳定性改进。",
    [string]$OutDir,
    [switch]$SkipLegacy,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
# PS 5.1 默认按 GBK 输出，中文提示会变乱码；固定成 UTF-8。
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Repo      = Split-Path -Parent $PSScriptRoot
$Kotlin    = Join-Path $Repo "kotlin-tv"
$Legacy    = "D:\Hermes\hg\legacy-android5\kotlin-tv"
$JavaHome  = "D:\Hermes\hg\jdk\jdk-17.0.20.1+1"
$env:JAVA_HOME = $JavaHome

if (-not $OutDir) { $OutDir = Join-Path $Repo ("releases\v" + $MainVersion) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Gradle {
    param([string]$ProjectDir, [string[]]$Tasks)
    $gradlew = Join-Path $ProjectDir "gradlew.bat"
    & $gradlew "-p" $ProjectDir @Tasks "--console=plain"
    if ($LASTEXITCODE -ne 0) { throw ("gradle 失败: " + ($Tasks -join " ")) }
}

function Copy-Apk {
    param([string]$From, [string]$Name)
    if (-not (Test-Path $From)) { throw "找不到构建产物: $From" }
    $target = Join-Path $OutDir $Name
    Copy-Item -Force $From $target
    Write-Host ("  -> " + $Name)
    return $target
}

Write-Host "== 主线 0.25.x ($MainVersion / $MainCode)" -ForegroundColor Cyan
if (-not $SkipBuild) {
    $env:HONGGUOTV_VERSION_NAME = $MainVersion
    $env:HONGGUOTV_VERSION_CODE = "$MainCode"

    Invoke-Gradle $Kotlin @(":app:assembleSwDebug")
    Copy-Apk (Join-Path $Kotlin "app\build\outputs\apk\sw\debug\app-sw-debug.apk") ("hongguotv-" + $MainVersion + "-sw-tv-debug.apk") | Out-Null

    Invoke-Gradle $Kotlin @(":app:assembleSwDebug", "-PminSdk=24")
    Copy-Apk (Join-Path $Kotlin "app\build\outputs\apk\sw\debug\app-sw-debug.apk") ("hongguotv-" + $MainVersion + "-sw-a7-debug.apk") | Out-Null

    Invoke-Gradle $Kotlin @(":app:assembleHwDebug", "-PminSdk=26", "-PscreenOrientation=unspecified")
    Copy-Apk (Join-Path $Kotlin "app\build\outputs\apk\hw\debug\app-hw-debug.apk") ("hongguotv-" + $MainVersion + "-hw-phone-debug.apk") | Out-Null
} else {
    Write-Host "  跳过编译，使用现有 build 产物" -ForegroundColor Yellow
    Copy-Apk (Join-Path $Kotlin "app\build\outputs\apk\sw\debug\app-sw-debug.apk") ("hongguotv-" + $MainVersion + "-sw-tv-debug.apk") | Out-Null
    Copy-Apk (Join-Path $Kotlin "app\build\outputs\apk\hw\debug\app-hw-debug.apk") ("hongguotv-" + $MainVersion + "-hw-phone-debug.apk") | Out-Null
    if (-not $SkipLegacy -and $A5Version) {
        Copy-Apk (Join-Path $Legacy "app\build\outputs\apk\debug\app-debug.apk") ("hongguotv-" + $A5Version + "-android5-debug.apk") | Out-Null
    }
}

$variants = [ordered]@{}
function Add-Variant {
    param([string]$Key, [string]$File, [int]$Code, [string]$Name)
    $path = Join-Path $OutDir $File
    if (-not (Test-Path $path)) { throw "缺少变体文件: $File" }
    $hash = (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLower()
    $variants[$Key] = [ordered]@{
        versionCode = $Code
        versionName = $Name
        file        = $File
        size        = (Get-Item $path).Length
        sha256      = $hash
    }
}

Add-Variant "sw-tv"    ("hongguotv-" + $MainVersion + "-sw-tv-debug.apk")    $MainCode    $MainVersion
if (-not $SkipBuild) {
    Add-Variant "sw-a7" ("hongguotv-" + $MainVersion + "-sw-a7-debug.apk")   $MainCode    $MainVersion
} elseif (Test-Path (Join-Path $OutDir ("hongguotv-" + $MainVersion + "-sw-a7-debug.apk"))) {
    Add-Variant "sw-a7" ("hongguotv-" + $MainVersion + "-sw-a7-debug.apk")   $MainCode    $MainVersion
}
Add-Variant "hw-phone" ("hongguotv-" + $MainVersion + "-hw-phone-debug.apk") $MainCode    $MainVersion

Write-Host "== 安卓 5 支线" -ForegroundColor Cyan
if (-not $SkipLegacy) {
    if (-not $A5Version -or -not $A5Code) { throw "未传 -A5Version / -A5Code；如不想更新安卓5分支请加 -SkipLegacy" }
    if (-not $SkipBuild) {
        $env:HONGGUOTV_VERSION_NAME = $A5Version
        $env:HONGGUOTV_VERSION_CODE = "$A5Code"
        Invoke-Gradle $Legacy @(":app:assembleDebug", "-PminSdk=21")
    }
    Copy-Apk (Join-Path $Legacy "app\build\outputs\apk\debug\app-debug.apk") ("hongguotv-" + $A5Version + "-android5-debug.apk") | Out-Null
    Add-Variant "a5" ("hongguotv-" + $A5Version + "-android5-debug.apk") $A5Code $A5Version
}

# update.json 不能带 BOM，org.json 解析会失败
$doc = [ordered]@{
    versionCode = $MainCode
    versionName = $MainVersion
    notes       = $Notes
    variants    = $variants
}
$json  = $doc | ConvertTo-Json -Depth 6
$utf8  = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $OutDir "update.json"), $json, $utf8)

$sums = Get-ChildItem $OutDir -Filter *.apk | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 -Path $_.FullName).Hash.ToLower()
    "$hash  $($_.Name)"
}
[System.IO.File]::WriteAllLines((Join-Path $OutDir "SHA256SUMS.txt"), $sums, $utf8)

Write-Host ""
Write-Host "完成：$OutDir" -ForegroundColor Green
Get-ChildItem $OutDir | ForEach-Object { Write-Host ("  " + $_.Name + "  " + [math]::Round($_.Length / 1MB, 1) + " MB") }
Write-Host ""
Write-Host "下一步：" -ForegroundColor Cyan
Write-Host ("  1. 到 https://cnb.cool/lzyr9010/hongguoTV-updates/-/releases/new 建 tag v" + $MainVersion)
Write-Host  "  2. 标题：hongguo TV $MainVersion"
Write-Host ("  3. 把上面 " + ((Get-ChildItem $OutDir).Count) + " 个文件全部拖进「上传本地」")
Write-Host  "  4. 发布后确认 /releases/latest 已指向新 tag，客户端即可收到更新"
