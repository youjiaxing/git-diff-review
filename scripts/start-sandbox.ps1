param(
    [Parameter(Mandatory = $true)][string]$IdePath,
    [string]$JdkPath = $env:JAVA_HOME,
    [switch]$Reuse,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$SandboxName = 'sandbox'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot\build-local.ps1" -IdePath $IdePath -JdkPath $JdkPath
$sandbox = Join-Path (Join-Path $root 'build') $SandboxName
$fixture = Join-Path $sandbox 'diffnav-fixture'
if ((Test-Path -LiteralPath "$fixture\.git") -and !$Reuse) { throw 'A sandbox fixture already exists. Use -Reuse to run again.' }
$options = Join-Path $sandbox 'config\options'
$pluginLib = Join-Path $sandbox 'plugins\goland-diff-navigation\lib'
$testLib = Join-Path $sandbox 'plugins\diffnav-smoke\lib'
New-Item -ItemType Directory -Path $fixture,$options,$pluginLib,$testLib -Force | Out-Null
Copy-Item -LiteralPath "$root\build\local\goland-diff-navigation\lib\goland-diff-navigation-0.1.0.jar" -Destination $pluginLib
$cp = "$IdePath\lib\*;$IdePath\plugins\vcs-git\lib\*;$root\build\local\classes"
$testClasses = Join-Path $sandbox 'smoke-classes'
$sources = @(Get-ChildItem -LiteralPath "$root\src\smoke\java" -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -cp $cp -d $testClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'Sandbox test compilation failed.' }
& "$JdkPath\bin\jar.exe" --create --file "$testLib\diffnav-smoke.jar" -C $testClasses . -C "$root\src\smoke\resources" .
if ($LASTEXITCODE -ne 0) { throw 'Sandbox test packaging failed.' }
Set-Content -LiteralPath "$options\git.xml" -Encoding utf8 -Value '<application><component name="Git.Application.Settings"><option name="STAGING_AREA_ENABLED" value="true"/></component></application>'
Set-Content -LiteralPath "$options\vcs.xml" -Encoding utf8 -Value '<application><component name="VcsEditorTab.Settings"><option name="openInNewWindow" value="false"/></component></application>'
if (!(Test-Path -LiteralPath "$fixture\.git")) {
Set-Content -LiteralPath "$fixture\go.mod" -Encoding utf8 -Value "module example.com/diffnavfixture`n`ngo 1.21.0"
Set-Content -LiteralPath "$fixture\main.go" -Encoding utf8 -Value "package main`n`nfunc main() { _ = B() }"
Set-Content -LiteralPath "$fixture\b.go" -Encoding utf8 -Value "package main`n`nfunc B() int { return C() }"
Set-Content -LiteralPath "$fixture\c.go" -Encoding utf8 -Value "package main`n`nfunc C() int { return Clean() }"
Set-Content -LiteralPath "$fixture\clean.go" -Encoding utf8 -Value "package main`n`nfunc Clean() int { return 1 }`nfunc UnchangedCaller() int { return B() }"
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`nfunc Partial() int { return B() }"
& git -C $fixture init --initial-branch=main | Out-Null
& git -C $fixture -c user.name='Diff Navigation Test' -c user.email='test@example.invalid' add .
& git -C $fixture -c user.name='Diff Navigation Test' -c user.email='test@example.invalid' commit -qm 'test: sandbox baseline'
if ($LASTEXITCODE -ne 0) { throw 'Fixture baseline failed.' }
Set-Content -LiteralPath "$fixture\b.go" -Encoding utf8 -Value "package main`n`nfunc B() int { return C() + 1 }"
& git -C $fixture add b.go
Set-Content -LiteralPath "$fixture\c.go" -Encoding utf8 -Value "package main`n`nfunc C() int { return Clean() + 2 }"
Set-Content -LiteralPath "$fixture\main.go" -Encoding utf8 -Value "package main`n`nfunc main() { _ = B() + 3 }"
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`nfunc Partial() int { return B() + 1 }"
& git -C $fixture add partial.go
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`n// worktree-only`nfunc Partial() int { return B() + 1 }"
}
$vmOptions = Join-Path $sandbox 'sandbox.vmoptions'
$originalOptions = Get-Content -LiteralPath "$IdePath\bin\goland64.exe.vmoptions"
$sandboxOptions = @(
    "-Didea.config.path=$sandbox/config",
    "-Didea.system.path=$sandbox/system",
    "-Didea.plugins.path=$sandbox/plugins",
    "-Didea.log.path=$sandbox/log",
    "-Ddiffnav.smoke.report=$sandbox/smoke-report.txt",
    '-Didea.trust.all.projects=true',
    '-Didea.config.imported.in.current.session=true',
    '-Djb.consents.confirmation.enabled=false',
    '-Dide.show.tips.on.startup.default.value=false'
)
Set-Content -LiteralPath $vmOptions -Encoding utf8 -Value ($originalOptions + $sandboxOptions)
$previous = $env:GOLAND_VM_OPTIONS
try {
    $env:GOLAND_VM_OPTIONS = $vmOptions
    $process = Start-Process -FilePath "$IdePath\bin\goland64.exe" -ArgumentList ('"' + $fixture + '"') -PassThru -WindowStyle Hidden
    Write-Output "Sandbox launcher PID: $($process.Id). Report: $sandbox\smoke-report.txt"
} finally { $env:GOLAND_VM_OPTIONS = $previous }
