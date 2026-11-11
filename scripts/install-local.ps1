param(
    [Parameter(Mandatory = $true)][string]$IdePath,
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$ConfigPath,
    [string]$SystemPath,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\plugin-version.ps1"
if (!(Test-Path -LiteralPath "$IdePath\product-info.json")) { throw 'IdePath must contain product-info.json.' }
$product = Get-Content -LiteralPath "$IdePath\product-info.json" -Raw | ConvertFrom-Json
if ($product.buildNumber -notlike '262.*') { throw 'This build currently targets IntelliJ platform 262 only.' }
if (!$product.dataDirectoryName -or [IO.Path]::GetFileName($product.dataDirectoryName) -ne $product.dataDirectoryName) { throw 'Unexpected IDE data directory name.' }
if (!$ConfigPath) { $ConfigPath = Join-Path $env:APPDATA "JetBrains\$($product.dataDirectoryName)" }
if (!$SystemPath) { $SystemPath = Join-Path $env:LOCALAPPDATA "JetBrains\$($product.dataDirectoryName)" }
$archive = Join-Path $root "build\local\git-diff-review-$pluginVersion.zip"
if (!(Test-Path -LiteralPath $archive)) { throw "Build the $pluginVersion ZIP first." }
if (!(Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw 'Specify JDK 25.' }
$work = Join-Path $root "build\install-v$($pluginVersion.Replace('.', ''))"
New-Item -ItemType Directory -Path $work -Force | Out-Null
$cp = "$work;$IdePath\lib\*"
& "$JdkPath\bin\javac.exe" -cp "$IdePath\lib\*" -d $work "$PSScriptRoot\QueuePluginUpdate.java"
if ($LASTEXITCODE -ne 0) { throw 'Installer helper compilation failed.' }
if ($VerifyOnly) {
    $simulation = Join-Path $work ('simulation-' + [guid]::NewGuid().ToString('N'))
    $pluginLib = Join-Path $simulation 'plugins\git-diff-review\lib'
    New-Item -ItemType Directory -Path $pluginLib -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $pluginLib 'old-version.jar') -Value 'isolated update fixture'
    $legacyLib = Join-Path $simulation 'plugins\goland-diff-navigation\lib'
    New-Item -ItemType Directory -Path $legacyLib -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $legacyLib 'legacy-version.jar') -Value 'old plugin ID fixture'
    & "$JdkPath\bin\java.exe" '--add-exports=java.base/sun.nio.fs=ALL-UNNAMED' -cp $cp QueuePluginUpdate simulate "$simulation\action.script" $archive "$simulation\plugins" $pluginVersion
    if ($LASTEXITCODE -ne 0) { throw 'Deferred installation simulation failed.' }
    return
}
$plugins = Join-Path ([IO.Path]::GetFullPath($ConfigPath)) 'plugins'
$plugin = Join-Path $plugins 'git-diff-review'
$legacyPlugin = Join-Path $plugins 'goland-diff-navigation'
if (!(Test-Path -LiteralPath $plugin) -and !(Test-Path -LiteralPath $legacyPlugin)) { throw 'Expected current or legacy plugin directory was not found.' }
$queueRoot = Join-Path ([IO.Path]::GetFullPath($SystemPath)) 'plugins'
$queuedArchiveDirectory = Join-Path $queueRoot "diffnav-update-$pluginVersion"
New-Item -ItemType Directory -Path $queuedArchiveDirectory -Force | Out-Null
$backup = Join-Path $work ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
foreach ($installedPlugin in @($legacyPlugin, $plugin)) {
    if (Test-Path -LiteralPath $installedPlugin) { Copy-Item -LiteralPath $installedPlugin -Destination $backup -Recurse }
}
$script = Join-Path $queueRoot 'action.script'
if (Test-Path -LiteralPath $script) { Copy-Item -LiteralPath $script -Destination (Join-Path $backup 'action.script.before-update') }
$queuedArchive = Join-Path $queuedArchiveDirectory "git-diff-review-$pluginVersion.zip"
Copy-Item -LiteralPath $archive -Destination $queuedArchive -Force
if ((Get-FileHash -LiteralPath $archive).Hash -ne (Get-FileHash -LiteralPath $queuedArchive).Hash) { throw 'Archive checksum mismatch.' }
& "$JdkPath\bin\java.exe" -cp $cp QueuePluginUpdate queue $script $queuedArchive $plugins $pluginVersion
if ($LASTEXITCODE -ne 0) { throw 'Scheduling plugin update failed.' }
Write-Output "Git Diff Review $pluginVersion is queued for next startup. Backup: $backup"
