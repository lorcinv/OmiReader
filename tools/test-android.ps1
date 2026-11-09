param(
    [string]$Adb = '',
    [string]$Serial = 'emulator-5580',
    [string]$Package = 'io.github.lorcinv.omireader.debug',
    [string[]]$Classes = @(
        'io.legado.app.OmiUiInstrumentedTest',
        'io.legado.app.OmiLocalFormatsInstrumentedTest',
        'io.legado.app.OmiV2InstrumentedTest',
        'io.legado.app.OmiSourceInstrumentedTest',
        'io.legado.app.LocalBookLinksInstrumentedTest',
        'io.legado.app.model.localBook.RichDocumentImportTest'
    )
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!$Adb) {
    $Adb = Join-Path (Split-Path -Parent $root) 'OmiComic\.cache\android-tools\platform-tools\adb.exe'
}
if (!(Test-Path -LiteralPath $Adb)) { throw 'Pass the Android platform-tools adb path with -Adb.' }
$logDir = Join-Path $root 'docs\verification\1.1.3'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$failed = @()
foreach ($testClass in $Classes) {
    $label = $testClass.Split('.')[-1]
    $log = Join-Path $logDir ($label + '.log')
    & $Adb -s $Serial shell am instrument -w -r -e class $testClass ($Package + '.test/androidx.test.runner.AndroidJUnitRunner') *> $log
    $result = Get-Content -LiteralPath $log -Raw
    if ($LASTEXITCODE -ne 0 -or $result -notmatch 'OK \(\d+ tests?\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        $failed += $label
        Write-Output ("FAIL {0}: {1}" -f $label, $log)
        Get-Content -LiteralPath $log -Tail 25
    } else {
        Write-Output ("PASS {0}: {1}" -f $label, $Matches[0])
    }
}
if ($failed.Count) { throw ('Android checks failed: ' + ($failed -join ', ')) }
