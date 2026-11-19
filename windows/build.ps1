$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'src'
$output = Join-Path $PSScriptRoot 'dist'
$compiler = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compiler) { throw '找不到 Windows 的 .NET Framework C# 編譯器。' }

New-Item -ItemType Directory -Force -Path $output | Out-Null
$data = Join-Path $source 'QuotaData.cs'
& $compiler /nologo /codepage:65001 /target:winexe /optimize+ "/win32icon:$source\widget.ico" "/out:$output\QuotaPocket.exe" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Web.Extensions.dll $data (Join-Path $source 'QuotaPocket.cs')
if ($LASTEXITCODE -ne 0) { throw "小工具編譯失敗（$LASTEXITCODE）。" }
& $compiler /nologo /codepage:65001 /target:exe /optimize+ "/out:$output\QuotaPocketBridge.exe" /r:System.Web.Extensions.dll $data (Join-Path $source 'QuotaBridge.cs')
if ($LASTEXITCODE -ne 0) { throw "Chrome 連接程式編譯失敗（$LASTEXITCODE）。" }

$extension = Join-Path $output 'Chrome'
New-Item -ItemType Directory -Force -Path $extension | Out-Null
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'Chrome') -File | Copy-Item -Destination $extension -Force
Write-Output "Windows 建置完成。開啟 $output\QuotaPocket.exe"
