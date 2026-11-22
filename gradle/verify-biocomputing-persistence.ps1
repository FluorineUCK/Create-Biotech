$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = [Guid]::NewGuid().ToString('N')
$directory = Join-Path $root "build/biocomputing-disk/$run"
New-Item -ItemType Directory -Path $directory | Out-Null
Write-Host "Independent disk persistence world: $directory"
Push-Location $root
try {
    foreach ($phase in @('seed', 'resume', 'verify')) {
        $log = Join-Path $directory "$phase-gradle.log"
        & .\gradlew.bat runGameTestServer -I 'gradle/biocomputing-gametest.init.gradle' `
            "-PbiocomputingDiskPhase=$phase" "-PbiocomputingDiskRun=$run" `
            --offline --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1G' *> $log
        $result = $LASTEXITCODE
        Get-Content -LiteralPath $log -Tail 12
        if ($result -ne 0) { throw "Disk phase $phase failed with exit code $result. See $log" }
        if (!(Select-String -LiteralPath $log -SimpleMatch "BIOCOMPUTING_DISK_PHASE $phase passed;" -Quiet) `
            -or !(Select-String -LiteralPath $log -SimpleMatch "BIOCOMPUTING_RPC_DISK_PHASE $phase passed;" -Quiet) `
            -or !(Select-String -LiteralPath $log -SimpleMatch 'Game test server shutting down' -Quiet)) {
            throw "Disk phase $phase did not report both its behavioral assertions and normal shutdown. See $log"
        }
    }
    Write-Host "All three independent JVM phases passed: $directory"
} finally {
    Pop-Location
}
