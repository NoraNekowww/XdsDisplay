$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$jdk   = 'E:\Alpha_nz_Files\_toolchain\jdk\bin\javac.exe'
$java  = 'E:\Alpha_nz_Files\_toolchain\jdk\bin\java.exe'
$relay = 'E:\Alpha_nz_Files\XdsRelay\app\src\main\java\com\nz\xdsrelay'
$disp  = 'E:\Alpha_nz_Files\XdsDisplay\app\src\main\java\com\nz\xdsdisplay'
$ver   = 'E:\Alpha_nz_Files\XdsDisplay\verify'
$out   = "$ver\out"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Output '=== 编译: 中转端编码器 + 终端解码器 + 互通测试 ==='
& $jdk -encoding UTF-8 -d $out `
    "$relay\BleCodec.java" `
    "$disp\BleUuids.java" `
    "$disp\CpsParser.java" `
    "$disp\LnParser.java" `
    "$ver\InteropCheck.java" 2>&1 | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output 'javac 编译失败'; exit 1 }

Write-Output '=== 运行互通测试 ==='
& $java -cp $out InteropCheck 2>&1 | Write-Output
Write-Output "EXIT=$LASTEXITCODE"
