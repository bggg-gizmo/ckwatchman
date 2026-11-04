param(
    [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($Python)) {
    $command = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $command) { $command = Get-Command python3 -ErrorAction SilentlyContinue }
    if ($null -eq $command) { throw 'Python 3 is required; pass -Python with its executable path.' }
    $Python = $command.Source
}
$Python = [IO.Path]::GetFullPath($Python)
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) { throw "Missing Python executable: $Python" }

$shellTest = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'test-source-contracts.sh'))
$match = [regex]::Match($shellTest, '(?s)python\s+-\s+"\$ROOT"\s+<<''PY''\r?\n(.*?)\r?\nPY\s*$')
if (-not $match.Success) { throw 'Unable to extract source-contract Python from test-source-contracts.sh' }
$temp = Join-Path ([IO.Path]::GetTempPath()) ("watchman-source-contracts-{0}.py" -f [Guid]::NewGuid().ToString('N'))
try {
    [IO.File]::WriteAllText($temp, $match.Groups[1].Value, [Text.UTF8Encoding]::new($false))
    & $Python $temp $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw "Source-contract tests failed: $LASTEXITCODE" }
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
}
