param(
    [Parameter(Mandatory = $true)][string]$IdePath,
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$ConfigPath = (Join-Path $env:APPDATA 'JetBrains\GoLand2026.2'),
    [string]$SystemPath = (Join-Path $env:LOCALAPPDATA 'JetBrains\GoLand2026.2'),
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$archive = Join-Path $root 'build\local\goland-diff-navigation-0.3.0.zip'
if (!(Test-Path -LiteralPath $archive)) { throw 'Build the 0.3.0 ZIP first.' }
if (!(Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw 'Specify JDK 25.' }
$work = Join-Path $root 'build\install-v030'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$cp = "$work;$IdePath\lib\*"
& "$JdkPath\bin\javac.exe" -cp "$IdePath\lib\*" -d $work "$PSScriptRoot\QueuePluginUpdate.java"
if ($LASTEXITCODE -ne 0) { throw 'Installer helper compilation failed.' }
if ($VerifyOnly) {
    $simulation = Join-Path $work ('simulation-' + [guid]::NewGuid().ToString('N'))
    $pluginLib = Join-Path $simulation 'plugins\goland-diff-navigation\lib'
    New-Item -ItemType Directory -Path $pluginLib -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $pluginLib 'old-version.jar') -Value 'isolated update fixture'
    & "$JdkPath\bin\java.exe" '--add-exports=java.base/sun.nio.fs=ALL-UNNAMED' -cp $cp QueuePluginUpdate simulate "$simulation\action.script" $archive "$simulation\plugins"
    if ($LASTEXITCODE -ne 0) { throw 'Deferred installation simulation failed.' }
    return
}
$plugins = Join-Path ([IO.Path]::GetFullPath($ConfigPath)) 'plugins'
$plugin = Join-Path $plugins 'goland-diff-navigation'
if (!(Test-Path -LiteralPath $plugin)) { throw 'Expected existing plugin directory was not found.' }
$queueRoot = Join-Path ([IO.Path]::GetFullPath($SystemPath)) 'plugins'
$queuedArchiveDirectory = Join-Path $queueRoot 'diffnav-update-0.3.0'
New-Item -ItemType Directory -Path $queuedArchiveDirectory -Force | Out-Null
$backup = Join-Path $work ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
Copy-Item -LiteralPath $plugin -Destination $backup -Recurse
$script = Join-Path $queueRoot 'action.script'
if (Test-Path -LiteralPath $script) { Copy-Item -LiteralPath $script -Destination (Join-Path $backup 'action.script.before-update') }
$queuedArchive = Join-Path $queuedArchiveDirectory 'goland-diff-navigation-0.3.0.zip'
Copy-Item -LiteralPath $archive -Destination $queuedArchive -Force
if ((Get-FileHash -LiteralPath $archive).Hash -ne (Get-FileHash -LiteralPath $queuedArchive).Hash) { throw 'Archive checksum mismatch.' }
& "$JdkPath\bin\java.exe" -cp $cp QueuePluginUpdate queue $script $queuedArchive $plugins
if ($LASTEXITCODE -ne 0) { throw 'Scheduling plugin update failed.' }
Write-Output "GoLand Diff Navigation 0.3.0 is queued for next startup. Backup: $backup"
