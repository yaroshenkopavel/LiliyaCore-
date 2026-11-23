param(
    [Parameter(Mandatory=$true)][string]$ProvenanceDirectory,
    [Parameter(Mandatory=$true)][string]$StoriesModelPath,
    [switch]$Stage
)
$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $ProvenanceDirectory 'semantic-onnx-provenance.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Missing provenance manifest: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$items = @(
    @{ Key='encoder'; Name='multilingual-e5-small-liliya-v0.1.onnx'; Path=(Join-Path $ProvenanceDirectory 'multilingual-e5-small-liliya-v0.1.onnx') },
    @{ Key='tokenizer'; Name='multilingual-e5-small-tokenizer-v0.1.onnx'; Path=(Join-Path $ProvenanceDirectory 'multilingual-e5-small-tokenizer-v0.1.onnx') },
    @{ Key='stories'; Name='stories15M-q4_0.gguf'; Path=$StoriesModelPath }
)
foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath $item.Path -PathType Leaf)) { throw "Missing fixture: $($item.Path)" }
    if ($item.Key -eq 'stories') {
        $expectedSize = 19077344
        $expectedSha = '6151b1929d7f5aa3385d9ddef3393e55587c0a55de661562322bc51dfda93a04'
    } else {
        $record = $manifest.($item.Key)
        $expectedSize = [long]$record.size
        $expectedSha = [string]$record.sha256
    }
    $actualSize = (Get-Item -LiteralPath $item.Path).Length
    $actualSha = (Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSize -ne $expectedSize -or $actualSha -ne $expectedSha.ToLowerInvariant()) {
        throw "Fixture mismatch: $($item.Name) size=$actualSize sha256=$actualSha"
    }
    Write-Output "PASS $($item.Name) size=$actualSize sha256=$actualSha"
}
if ($Stage) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $target = Join-Path $repoRoot 'android-app\src\androidTest\assets'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    foreach ($item in $items) { Copy-Item -LiteralPath $item.Path -Destination (Join-Path $target $item.Name) -Force }
    Write-Output "STAGED $target"
} else {
    Write-Output 'VERIFY_ONLY (no files staged)'
}
