param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$AppApk,
    [string]$TestApk,
    [string]$OutputDir = ".\asf-h-usb-adb-evidence"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $Adb @Args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}

function Get-ThermalSnapshot {
    $raw = (& $Adb shell dumpsys thermalservice 2>&1 | Out-String)
    $cpu = [regex]::Matches($raw, 'Temperature\{mValue=([0-9.]+), mType=0, mName=CPU, mStatus=([0-9]+)\}') |
        ForEach-Object { [double]$_.Groups[1].Value }
    $skin = [regex]::Matches($raw, 'Temperature\{mValue=([0-9.]+), mType=3, mName=SKIN, mStatus=([0-9]+)\}') |
        ForEach-Object { [double]$_.Groups[1].Value }
    $battery = [regex]::Matches($raw, 'Temperature\{mValue=([0-9.]+), mType=2, mName=BATTERY, mStatus=([0-9]+)\}') |
        ForEach-Object { [double]$_.Groups[1].Value }
    $status = [regex]::Match($raw, 'Thermal Status:\s*([0-9]+)')
    [pscustomobject]@{
        status = if ($status.Success) { [int]$status.Groups[1].Value } else { $null }
        cpuSnapshotMaxC = if ($cpu) { ($cpu | Measure-Object -Maximum).Maximum } else { $null }
        skinSnapshotMaxC = if ($skin) { ($skin | Measure-Object -Maximum).Maximum } else { $null }
        batterySnapshotMaxC = if ($battery) { ($battery | Measure-Object -Maximum).Maximum } else { $null }
    }
}

function Get-BatterySnapshot {
    $raw = (& $Adb shell dumpsys battery 2>&1 | Out-String)
    $level = [regex]::Match($raw, '(?m)^\s*level:\s*(\d+)')
    $temp = [regex]::Match($raw, '(?m)^\s*temperature:\s*(\d+)')
    $voltage = [regex]::Match($raw, '(?m)^\s*voltage:\s*(\d+)')
    [pscustomobject]@{
        level = if ($level.Success) { [int]$level.Groups[1].Value } else { $null }
        temperatureC = if ($temp.Success) { [double]$temp.Groups[1].Value / 10.0 } else { $null }
        voltageMv = if ($voltage.Success) { [int]$voltage.Groups[1].Value } else { $null }
    }
}

function Get-MemorySample {
    $raw = (& $Adb shell dumpsys meminfo pro.liliya.app 2>&1 | Out-String)
    $match = [regex]::Match($raw, 'TOTAL PSS:\s*(\d+)\s+TOTAL RSS:\s*(\d+)')
    if (-not $match.Success) { return $null }
    [pscustomobject]@{
        timestampUtc = [DateTime]::UtcNow.ToString('o')
        pssKb = [int]$match.Groups[1].Value
        rssKb = [int]$match.Groups[2].Value
    }
}

if (-not (Test-Path $AppApk)) { throw "Missing AppApk: $AppApk" }
if (-not (Test-Path $TestApk)) { throw "Missing TestApk: $TestApk" }

Invoke-Adb devices '-l'
& $Adb install -r -t $AppApk
if ($LASTEXITCODE -ne 0) { throw "App install failed" }
& $Adb install -r -t $TestApk
if ($LASTEXITCODE -ne 0) { throw "Test install failed" }

$results = @()
foreach ($mode in @('FULL_ONLY', 'HIERARCHICAL')) {
    $thermalBefore = Get-ThermalSnapshot
    $batteryBefore = Get-BatterySnapshot
    $stdout = Join-Path $OutputDir "instrument-$mode.out.txt"
    $stderr = Join-Path $OutputDir "instrument-$mode.err.txt"

    $args = @(
        'shell','am','instrument','-w','-r',
        '-e','class','pro.liliya.app.AsfHierarchicalPhysicalResourceInstrumentedTest',
        '-e','asf_h_mode',$mode,
        'pro.liliya.app.test/androidx.test.runner.AndroidJUnitRunner'
    )
    $started = [DateTime]::UtcNow
    $process = Start-Process -FilePath $Adb -ArgumentList $args -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $samples = @()
    while (-not $process.HasExited) {
        $sample = Get-MemorySample
        if ($null -ne $sample) { $samples += $sample }
        Start-Sleep -Milliseconds 100
        $process.Refresh()
    }
    $ended = [DateTime]::UtcNow

    $instrumentation = Get-Content $stdout -Raw
    $passed = $instrumentation -match 'INSTRUMENTATION_CODE:\s*-1'
    $summaryName = if ($mode -eq 'FULL_ONLY') { 'asf-h-physical-full-only.json' } else { 'asf-h-physical-hierarchical.json' }
    $summary = (& $Adb shell run-as pro.liliya.app cat "files/$summaryName" | Out-String) | ConvertFrom-Json
    $thermalAfter = Get-ThermalSnapshot
    $batteryAfter = Get-BatterySnapshot
    $pss = @($samples | ForEach-Object { $_.pssKb })
    $rss = @($samples | ForEach-Object { $_.rssKb })

    $results += [pscustomobject]@{
        mode = $mode
        instrumentationPassed = $passed
        wallElapsedMs = [int][Math]::Round(($ended - $started).TotalMilliseconds)
        summaryActualElapsedMillis = [int]$summary.actualElapsedMillis
        sampleCount = $samples.Count
        pssPeakKb = if ($pss.Count) { ($pss | Measure-Object -Maximum).Maximum } else { $null }
        pssAvgKb = if ($pss.Count) { [int][Math]::Round(($pss | Measure-Object -Average).Average) } else { $null }
        rssPeakKb = if ($rss.Count) { ($rss | Measure-Object -Maximum).Maximum } else { $null }
        rssAvgKb = if ($rss.Count) { [int][Math]::Round(($rss | Measure-Object -Average).Average) } else { $null }
        thermalBefore = $thermalBefore
        thermalAfter = $thermalAfter
        batteryBefore = $batteryBefore
        batteryAfter = $batteryAfter
        qualityFingerprint = $summary.qualityFingerprint
        modeledWallClockMillis = [int]$summary.modeledWallClockMillis
        modeledInferenceUnits = [int]$summary.modeledInferenceUnits
        modeledContextBytes = [int]$summary.modeledContextBytes
        modeledRetrievalItems = [int]$summary.modeledRetrievalItems
        samples = $samples
    }
}

$evidence = [pscustomobject]@{
    evidenceClass = 'asf-h-usb-adb-physical-resource-evidence-v1'
    sourceCommit = (git rev-parse HEAD).Trim()
    deviceModel = (& $Adb shell getprop ro.product.model | Out-String).Trim()
    abi = (& $Adb shell getprop ro.product.cpu.abi | Out-String).Trim()
    sdk = [int]((& $Adb shell getprop ro.build.version.sdk | Out-String).Trim())
    transport = 'usb-adb'
    verdict = 'MEASURED_NO_PRODUCT_BUDGET_APPLIED'
    results = $results
}
$evidence | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $OutputDir 'asf-h-usb-adb-physical-resource-evidence.json')
$evidence | ConvertTo-Json -Depth 6
