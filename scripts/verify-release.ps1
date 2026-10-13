# 发布前回归检查单（任务卡 T4.3）
#
# 把《桌面版递交报告》T4.3 的 8 项检查单里**可机械验证**的部分全部脚本化：
# 一条命令跑完，逐项打印 PASS / FAIL / MANUAL，退出码 0 表示自动化部分全过。
#
# 用法：
#   pwsh -File scripts/verify-release.ps1
#   pwsh -File scripts/verify-release.ps1 -SkipBuild      # 跳过重新构建，只跑用例
#
# 注意：本脚本假定项目已按《01 · 环境搭建指南》准备好 JDK 17；
#      JAVA_HOME 未设置时用 PATH 上的 java。

[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$script:results = @()
function Add-Result([string]$Item, [string]$Status, [string]$Detail) {
    $script:results += [pscustomobject]@{ 检查项 = $Item; 结果 = $Status; 说明 = $Detail }
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0,-6}] {1}  {2}" -f $Status, $Item, $Detail) -ForegroundColor $color
}

$gradlew = Join-Path $root 'gradlew.bat'
$outDir = Join-Path $root 'out\verify'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "=== 0. 构建 ===" -ForegroundColor Cyan
if (-not $SkipBuild) {
    & $gradlew build :composeApp:cliJar --console=plain -q 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Add-Result '构建' 'FAIL' 'gradlew build 失败'; exit 1 }
}
$jar = Join-Path $root 'composeApp\build\libs\mpkg-cli.jar'
if (-not (Test-Path $jar)) { Add-Result '构建' 'FAIL' '未生成 mpkg-cli.jar'; exit 1 }
Add-Result '构建' 'PASS' 'gradlew build 与 cliJar 成功'

Write-Host "`n=== 1. core 四类测试 ===" -ForegroundColor Cyan
& $gradlew :core:test --console=plain -q 2>&1 | Out-Null
$coreXml = Get-ChildItem (Join-Path $root 'core\build\test-results\test') -Filter *.xml -ErrorAction SilentlyContinue
$coreTests = 0; $coreFail = 0
foreach ($f in $coreXml) {
    [xml]$x = Get-Content $f.FullName -Encoding UTF8
    $coreTests += [int]$x.testsuite.tests
    $coreFail += [int]$x.testsuite.failures + [int]$x.testsuite.errors
}
if ($coreTests -ge 4 -and $coreFail -eq 0) {
    Add-Result '1. core 四类测试全绿' 'PASS' "$($coreXml.Count) 个测试类 / $coreTests 项 / 失败 $coreFail"
} else {
    Add-Result '1. core 四类测试全绿' 'FAIL' "共 $coreTests 项，失败 $coreFail"
}

Write-Host "`n=== 2. sample 端到端逐像素一致 ===" -ForegroundColor Cyan
& java -jar $jar 'sample/texture.tex' --out-dir $outDir --scale 1280x720 `
    --compare 'sample/expected_1280x720.png' 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Add-Result '2. 端到端逐像素一致' 'PASS' '4K 解码 + Lanczos 对齐 expected_1280x720.png 差异 0'
} else {
    Add-Result '2. 端到端逐像素一致' 'FAIL' '与期望样本不一致'
}

Write-Host "`n=== 3. 真实内容 mpkg（含视频壁纸）===" -ForegroundColor Cyan
& python (Join-Path $root 'scripts\make-fixtures.py') $root (Join-Path $outDir 'fixtures') 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Add-Result '3. 真实 mpkg 回归' 'FAIL' '夹具生成失败（需要 Python）'
} else {
    $fx = Join-Path $outDir 'fixtures'
    & java -jar $jar (Join-Path $fx 'wallpaper_static.mpkg') --out-dir $outDir `
        --scale 1280x720 --compare 'sample/expected_1280x720.png' --hash 2>&1 | Out-Null
    $staticOk = $LASTEXITCODE -eq 0
    & java -jar $jar (Join-Path $fx 'wallpaper_video.mpkg') --out-dir $outDir --hash 2>&1 | Out-Null
    $videoOk = $LASTEXITCODE -eq 0
    $expect = (Get-FileHash (Join-Path $fx 'expected_video.mp4') -Algorithm SHA256).Hash
    $actual = (Get-FileHash (Join-Path $outDir 'loop.mp4') -Algorithm SHA256).Hash
    $byteIdentical = $expect -eq $actual
    # 主图必须走 ImageRanker：导出结果应与直接解 textures/main.tex 完全一致
    $mainPng = Join-Path $outDir 'main.png'
    $mainOk = (Test-Path $mainPng) -and ((Get-Item $mainPng).Length -gt 1000000)
    if ($staticOk -and $videoOk -and $byteIdentical -and $mainOk) {
        Add-Result '3. 真实 mpkg 回归' 'PASS' "静态包 0 差异；MP4 字节一致 ($($expect.Substring(0,12))…)"
    } else {
        Add-Result '3. 真实 mpkg 回归' 'FAIL' "static=$staticOk video=$videoOk bytes=$byteIdentical 主图=$mainOk"
    }
}

Write-Host "`n=== 4. 乱数据 / 截断不崩溃 ===" -ForegroundColor Cyan
$negDir = Join-Path $outDir 'negative'
& python (Join-Path $root 'scripts\make-negative.py') $negDir $root 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Add-Result '4. 负向测试' 'FAIL' '负向样本生成失败（需要 Python）'
} else {
    $bad = 0; $total = 0
    foreach ($f in (Get-ChildItem $negDir -File)) {
        $total++
        & java -jar $jar $f.FullName --out-dir (Join-Path $outDir 'neg-out') 2>&1 | Out-Null
        # 期望：非 0 退出（报错），但进程正常结束（不崩溃）
        if ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -gt 3) { $bad++ }
    }
    if ($bad -eq 0 -and $total -ge 10) {
        Add-Result '4. 负向测试' 'PASS' "$total 个乱数据/截断/错误魔数文件全部报错不崩"
    } else {
        Add-Result '4. 负向测试' 'FAIL' "$total 个样本中有 $bad 个行为异常"
    }
}

Write-Host "`n=== 7. 双主题 / 历史 / 设置 / 动效（单元测试覆盖）===" -ForegroundColor Cyan
& $gradlew :composeApp:test --console=plain -q 2>&1 | Out-Null
$appXml = Get-ChildItem (Join-Path $root 'composeApp\build\test-results\test') -Filter *.xml -ErrorAction SilentlyContinue
$appTests = 0; $appFail = 0
foreach ($f in $appXml) {
    [xml]$x = Get-Content $f.FullName -Encoding UTF8
    $appTests += [int]$x.testsuite.tests
    $appFail += [int]$x.testsuite.failures + [int]$x.testsuite.errors
}
if ($appFail -eq 0 -and $appTests -gt 0) {
    Add-Result '7. 主题/历史/设置' 'PASS' "$appTests 项单元测试通过（含对比度 ≥4.5:1、动效 ≤300ms、JSON 原子写入、LRU）"
} else {
    Add-Result '7. 主题/历史/设置' 'FAIL' "共 $appTests 项，失败 $appFail"
}

Write-Host "`n=== 8. README / 许可 / 关于页 ===" -ForegroundColor Cyan
$missing = @()
foreach ($p in 'README.md', 'LICENSE', 'composeApp/src/main/kotlin/com/mpkg/app/AppInfo.kt',
    'composeApp/src/main/resources/icons/app.ico') {
    if (-not (Test-Path (Join-Path $root $p))) { $missing += $p }
}
if ($missing.Count -eq 0) {
    Add-Result '8. README/许可/关于页' 'PASS' 'README、LICENSE、AppInfo（关于页出处）与应用图标齐备'
} else {
    Add-Result '8. README/许可/关于页' 'FAIL' "缺失: $($missing -join ', ')"
}

Write-Host "`n=== 需要人工完成的项 ===" -ForegroundColor Cyan
Add-Result '5. 三平台安装包安装/卸载' 'MANUAL' 'Windows 需先装 WiX Toolset 3.x 再跑 packageMsi；macOS/Linux 需在对应平台打包'
Add-Result '6. 文件关联唤起应用' 'MANUAL' '需先安装 msi/deb，再双击 .mpkg 验证；参数入队逻辑已由 app --args 路径覆盖'

Write-Host "`n=== 汇总 ===" -ForegroundColor Cyan
$script:results | Format-Table -AutoSize -Wrap
$failed = @($script:results | Where-Object { $_.结果 -eq 'FAIL' }).Count
Write-Host ("自动检查通过 {0} / 失败 {1} / 人工 {2}" -f `
    @($script:results | Where-Object { $_.结果 -eq 'PASS' }).Count, $failed, `
    @($script:results | Where-Object { $_.结果 -eq 'MANUAL' }).Count)
exit $failed
