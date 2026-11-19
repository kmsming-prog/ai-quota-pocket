$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'src'
$tests = Join-Path $PSScriptRoot 'tests'
$output = Join-Path $PSScriptRoot 'dist'
$compiler = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compiler) { throw '找不到 Windows 的 .NET Framework C# 編譯器。' }

New-Item -ItemType Directory -Force -Path $output | Out-Null
$fake = Join-Path $output 'FakeQuotaCli.exe'
$smoke = Join-Path $output 'Smoke.exe'
& $compiler /nologo /codepage:65001 /target:exe "/out:$fake" /r:System.Web.Extensions.dll (Join-Path $tests 'FakeQuotaCli.cs')
if ($LASTEXITCODE -ne 0) { throw '測試工具編譯失敗。' }

$sources = @('QuotaData.cs','QuotaSources.cs','QuotaPocket.cs') | ForEach-Object { Join-Path $source $_ }
& $compiler /nologo /codepage:65001 /main:QuotaSourcesSmoke /target:exe "/out:$smoke" /r:System.Web.Extensions.dll /r:System.Windows.Forms.dll /r:System.Drawing.dll @sources (Join-Path $tests 'QuotaSourcesSmoke.cs')
if ($LASTEXITCODE -ne 0) { throw '測試程式編譯失敗。' }
& $smoke $fake
if ($LASTEXITCODE -ne 0) { throw 'Windows 測試未通過。' }
