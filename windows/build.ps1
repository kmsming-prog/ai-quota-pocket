$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'src'
$output = Join-Path $PSScriptRoot 'dist'
$compiler = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compiler) { throw '找不到 Windows 的 .NET Framework C# 編譯器。' }

New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @('QuotaData.cs','QuotaSources.cs','QuotaPocket.cs') | ForEach-Object { Join-Path $source $_ }
& $compiler /nologo /codepage:65001 /target:winexe /optimize+ "/win32icon:$source\widget.ico" "/out:$output\QuotaPocket.exe" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll @sources
if ($LASTEXITCODE -ne 0) { throw "小工具編譯失敗（$LASTEXITCODE）。" }
Write-Output "Windows 建置完成。開啟 $output\QuotaPocket.exe"
