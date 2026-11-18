param(
    [Parameter(Mandatory=$true)][string]$IdePath,
    [Parameter(Mandatory=$true)][string]$JdkPath,
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [Parameter(Mandatory=$true)][string]$SystemPath,
    [Parameter(Mandatory=$true)][string]$Archive,
    [Parameter(Mandatory=$true)][ValidatePattern('^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$')][string]$Version,
    [switch]$PrepareOnly,
    [switch]$NoRelaunch,
    [int[]]$WaitProcessId
)
$ErrorActionPreference='Stop'
$configuration=[IO.Path]::GetFullPath($ConfigPath)
$plugins=Join-Path $configuration 'plugins'
$plugin=Join-Path $plugins 'git-diff-review'
$legacy=Join-Path $plugins 'goland-diff-navigation'
if (!(Test-Path -LiteralPath $plugin) -and !(Test-Path -LiteralPath $legacy)) { throw 'Expected installed plugin directory not found.' }
$pending=Join-Path $SystemPath 'plugins/diffnav-offline-pending.json'
if (Test-Path -LiteralPath $pending) {
    $previous=Get-Content -LiteralPath $pending -Raw | ConvertFrom-Json
    $active=Get-CimInstance Win32_Process -Filter "ProcessId=$($previous.processId)" -ErrorAction SilentlyContinue
    if ($active -and $active.CommandLine -and $active.CommandLine.Contains($previous.planPath)) { throw "An updater is already waiting. Status: $($previous.statusPath)" }
}
$runId="$Version-"+[guid]::NewGuid().ToString('N')
$session=Join-Path $SystemPath "plugins/diffnav-offline-$runId"
$staging=Join-Path $configuration "diffnav-update/staged-$runId"
New-Item -ItemType Directory -Path $session,$staging -Force | Out-Null
$payload=Join-Path $session "git-diff-review-$Version.zip"
Copy-Item -LiteralPath $Archive -Destination $payload
if ((Get-FileHash -LiteralPath $Archive).Hash -ne (Get-FileHash -LiteralPath $payload).Hash) { throw 'Copied archive checksum mismatch.' }
Expand-Archive -LiteralPath $payload -DestinationPath $staging
$stagedPlugin=Join-Path $staging 'git-diff-review'
$jar=Join-Path $stagedPlugin "lib/git-diff-review-$Version.jar"
if (!(Test-Path -LiteralPath $jar)) { throw 'Archive does not contain the expected versioned JAR.' }
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try {
    $descriptor=$zip.GetEntry('META-INF/plugin.xml')
    $reader=[IO.StreamReader]::new($descriptor.Open())
    try { [xml]$xml=$reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($xml.'idea-plugin'.id -ne 'io.github.youjiaxing.git-diff-review' -or $xml.'idea-plugin'.version -ne $Version) { throw 'Plugin ID or version mismatch.' }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'QueuePluginUpdate.java') -Destination $session
& "$JdkPath/bin/javac.exe" -cp "$IdePath/lib/*" -d $session (Join-Path $session 'QueuePluginUpdate.java')
if ($LASTEXITCODE -ne 0) { throw 'Offline update helper compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'complete-local-update.ps1') -Destination $session
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline-update-utils.ps1') -Destination $session
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($plugins.ToLowerInvariant()))).Substring(0,24)
$ideExecutable=[IO.Path]::GetFullPath((Join-Path $IdePath 'bin/goland64.exe'))
$waitProcesses=@(Get-CimInstance Win32_Process -Filter "Name='goland64.exe'" | Where-Object {
    $_.ExecutablePath -and [IO.Path]::GetFullPath($_.ExecutablePath) -eq $ideExecutable -and (!$WaitProcessId -or $_.ProcessId -in $WaitProcessId)
} | ForEach-Object { @{processId=$_.ProcessId;created=$_.CreationDate.ToUniversalTime().Ticks} })
$plan=[ordered]@{
 version=$Version; archive=$payload; archiveHash=(Get-FileHash -LiteralPath $payload).Hash
 jarHash=(Get-FileHash -LiteralPath $jar).Hash; stagedPlugin=$stagedPlugin
 plugin=$plugin; legacy=$legacy; plugins=$plugins
 backup=(Join-Path $configuration "diffnav-update/backup-$runId")
 actionScript=(Join-Path $SystemPath 'plugins/action.script')
 statusPath=(Join-Path $session 'status.json'); mutexName="Local\DiffnavOfflineUpdate-$hash"
 ideExecutable=$ideExecutable; waitProcesses=$waitProcesses
 javaExecutable=[IO.Path]::GetFullPath((Join-Path $JdkPath 'bin/java.exe'))
 helperClasspath="$session;$IdePath/lib/*"; relaunch=(!$NoRelaunch)
}
$planPath=Join-Path $session 'plan.json';$plan | ConvertTo-Json | Set-Content -LiteralPath $planPath -Encoding utf8
if ($PrepareOnly) { Write-Output $planPath; return }
$worker=Join-Path $session 'complete-local-update.ps1'
$workerArguments=@('-NoProfile','-File',('"'+$worker+'"'),'-PlanPath',('"'+$planPath+'"'))
$process=Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList $workerArguments -WindowStyle Hidden -PassThru
[ordered]@{processId=$process.Id;planPath=$planPath;statusPath=$plan.statusPath} | ConvertTo-Json | Set-Content -LiteralPath $pending -Encoding utf8
Write-Output "Offline updater PID $($process.Id) prepared for $Version. Status: $($plan.statusPath)"
