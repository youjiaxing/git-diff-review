$pluginProperties = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\gradle.properties')
$pluginVersionLines = @($pluginProperties | Where-Object { $_ -match '^pluginVersion=' })
if ($pluginVersionLines.Count -ne 1) { throw 'Expected one pluginVersion in gradle.properties.' }
$pluginVersion = $pluginVersionLines[0].Substring('pluginVersion='.Length)
if ($pluginVersion -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'Expected a stable major.minor.patch version.' }
