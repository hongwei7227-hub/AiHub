# Plan N v2 — Java 包名 com.hmdp → com.cityaihub 全量替换
# 关键设计:
#   1. 用 [System.IO.File]::ReadAllText / WriteAllText + UTF8Encoding($false) 严格保证
#      不写 BOM(PS 5.1 Set-Content -Encoding UTF8 会写 BOM,导致 javac 拒绝)
#   2. 完整保留原始字节流(不动 CRLF/LF 行尾,不动文末换行)
#   3. 3 条 pattern: com.hmdp / com/hmdp / hmdp-(producer|seckill|order|consumer)
#   4. 扫描后缀含 *.md(否则 docs/简历信息/AGENT_AND_RAG_INTERNALS.md 等链接全 404)
#   5. 排除 target/ node_modules/ frontend-vue/dist/
#
# 执行: powershell -ExecutionPolicy Bypass -File scripts\rename_package.ps1

#requires -Version 5.1
$ErrorActionPreference = 'Stop'

$exts = @('*.java', '*.yaml', '*.yml', '*.xml', '*.properties', '*.md')
$root = 'F:\project\CityAIHub-master'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

Write-Host "[rename] scanning $root ..."
$files = Get-ChildItem -Path $root -Recurse -File -Include $exts |
    Where-Object {
        $_.FullName -notmatch '\\target\\' -and
        $_.FullName -notmatch '\\node_modules\\' -and
        $_.FullName -notmatch '\\frontend-vue\\dist\\'
    }
Write-Host "[rename] $($files.Count) files to check"

$count = 0
foreach ($f in $files) {
    $content = [System.IO.File]::ReadAllText($f.FullName, $utf8NoBom)
    if ($content -match 'com\.hmdp' -or $content -match 'com/hmdp' -or $content -match 'hmdp-(producer|seckill|order|consumer)') {
        $new = $content `
            -replace 'com\.hmdp', 'com.cityaihub' `
            -replace 'com/hmdp', 'com/cityaihub' `
            -replace 'hmdp-(producer|seckill|order|consumer)', 'cityaihub-$1'
        if ($new -ne $content) {
            [System.IO.File]::WriteAllText($f.FullName, $new, $utf8NoBom)
            $count++
            Write-Host "patched: $($f.FullName)"
        }
    }
}
Write-Host ""
Write-Host "[rename] DONE. Total files patched: $count"
