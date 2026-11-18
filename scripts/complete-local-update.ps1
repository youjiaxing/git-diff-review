param([Parameter(Mandatory = $true)][string]$PlanPath, [switch]$Once)
$ErrorActionPreference = 'Stop'
$plan = Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json
. "$PSScriptRoot/offline-update-utils.ps1"
$recoveryIncomplete=$false
function Write-State([string]$phase, [string]$message) {
    [ordered]@{ phase=$phase; message=$message; time=(Get-Date).ToString('o'); processId=$PID; version=$plan.version } |
        ConvertTo-Json | Set-Content -LiteralPath $plan.statusPath -Encoding utf8
}
function Test-Idle {
    $running = @(Get-CimInstance Win32_Process -Filter "Name='goland64.exe'" | Where-Object {
        $candidate=$_
        $candidate.ExecutablePath -and [IO.Path]::GetFullPath($candidate.ExecutablePath) -eq $plan.ideExecutable -and
            @($plan.waitProcesses | Where-Object { $_.processId -eq $candidate.ProcessId -and $_.created -eq $candidate.CreationDate.ToUniversalTime().Ticks }).Count -gt 0
    })
    if ($running.Count) { return $false }
    foreach ($pluginDirectory in @($plan.plugin, $plan.legacy)) {
        if (!(Test-Path -LiteralPath $pluginDirectory)) { continue }
        foreach ($jar in @(Get-ChildItem -LiteralPath $pluginDirectory -Filter '*.jar' -Recurse -File)) {
            try { $held = [IO.File]::Open($jar.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None); $held.Dispose() }
            catch [IO.IOException] { return $false }
        }
    }
    return $true
}
function Verify-Payload {
    if (Test-Path -LiteralPath $plan.plugin) {
        foreach ($existingJar in @(Get-ChildItem -LiteralPath $plan.plugin -Filter '*.jar' -Recurse -File)) {
            if ($existingJar.Name -match '^git-diff-review-(\d+\.\d+\.\d+)\.jar$' -and [version]$Matches[1] -gt [version]$plan.version) {
                throw 'A newer plugin version is already installed; refusing an outdated pending update.'
            }
        }
    }
    if ((Get-FileHash -LiteralPath $plan.archive -Algorithm SHA256).Hash -ne $plan.archiveHash) { throw 'Update archive checksum changed.' }
    $jar = Join-Path $plan.stagedPlugin "lib/git-diff-review-$($plan.version).jar"
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $plan.jarHash) { throw 'Staged plugin checksum changed.' }
    if (@(Get-ChildItem -LiteralPath (Join-Path $plan.stagedPlugin 'lib') -File).Count -ne 1) { throw 'Staged plugin contains unexpected files.' }
}
$updateMutex = [Threading.Mutex]::new($false, $plan.mutexName)
$ownsMutex = $false
try {
    try { $ownsMutex = $updateMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (!$ownsMutex) { throw 'Another offline plugin updater is already active.' }
    Verify-Payload
    Write-State 'waiting' 'Close the selected GoLand processes normally and do not reopen them manually. This helper will install and relaunch after all target JAR locks are released. No process will be stopped.'
    while (!(Test-Idle)) { if ($Once) { exit 2 }; Start-Sleep -Seconds 2 }
    Verify-Payload
    Write-State 'installing' 'IDE is closed; preserving old plugin directories before replacement.'
    New-Item -ItemType Directory -Path $plan.backup -Force | Out-Null
    if (Test-Path -LiteralPath $plan.actionScript) { Copy-Item -LiteralPath $plan.actionScript -Destination (Join-Path $plan.backup 'action.script.before-update') }
    # The IDE's failed queue may have been consumed; remove only our own remaining commands.
    & $plan.javaExecutable -cp $plan.helperClasspath QueuePluginUpdate clear-own $plan.actionScript $plan.plugins
    if ($LASTEXITCODE -ne 0) { throw 'Could not preserve unrelated startup commands.' }
    $moved = [Collections.Generic.List[object]]::new()
    $installed = $false
    try {
        if (!(Test-Idle)) { throw 'GoLand started again; close it and retry the prepared updater.' }
        foreach ($pluginDirectory in @($plan.legacy, $plan.plugin)) {
            if (Test-Path -LiteralPath $pluginDirectory) {
                $destination = Join-Path $plan.backup ([IO.Path]::GetFileName($pluginDirectory))
                [IO.Directory]::Move($pluginDirectory, $destination)
                $moved.Add(@{original=$pluginDirectory;backup=$destination})
            }
        }
        [IO.Directory]::Move($plan.stagedPlugin, $plan.plugin); $installed = $true
        $installedJar = Join-Path $plan.plugin "lib/git-diff-review-$($plan.version).jar"
        if ((Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash -ne $plan.jarHash) { throw 'Installed plugin checksum mismatch.' }
        if (@(Get-ChildItem -LiteralPath (Join-Path $plan.plugin 'lib') -File).Count -ne 1) { throw 'Installed plugin contains mixed versions.' }
    } catch {
        $originalFailure = $_
        $remaining=@($moved.ToArray())
        do {
            $recovery=Restore-PluginDirectories $plan.plugin (Join-Path $plan.backup 'failed-new-plugin') $installed $remaining
            $installed=$recovery.newInstalled;$remaining=@($recovery.pending)
            $recoveryIncomplete=$installed -or $remaining.Count -gt 0
            if ($recoveryIncomplete) {
                Write-State 'recovery_pending' ("Install failed: " + $originalFailure.Exception.Message + "; recovery waiting: " + ($recovery.errors -join '; '))
                if ($Once) { throw $originalFailure }
                Start-Sleep -Seconds 2
            }
        } while ($recoveryIncomplete)
        Write-State 'failed_restored' ("Install failed; all old directories restored: " + $originalFailure.Exception.Message)
        throw $originalFailure
    }
    Write-State 'installed' 'Installed and verified the requested plugin version; complete old directories remain in the backup.'
    if ($plan.relaunch) {
        Start-Process -FilePath $plan.ideExecutable -WindowStyle Hidden | Out-Null
        Write-State 'installed' 'Installed and verified the requested plugin version; GoLand launch requested.'
    }
} catch {
    if (!$recoveryIncomplete -and (!(Test-Path -LiteralPath $plan.statusPath) -or (Get-Content -LiteralPath $plan.statusPath -Raw | ConvertFrom-Json).phase -ne 'failed_restored')) {
        Write-State 'failed' $_.Exception.Message
    }
    throw
} finally {
    if ($ownsMutex) { $updateMutex.ReleaseMutex() }
    $updateMutex.Dispose()
}
