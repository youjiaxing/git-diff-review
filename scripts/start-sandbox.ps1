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
$pluginLib = Join-Path $sandbox 'plugins\git-diff-review\lib'
$testLib = Join-Path $sandbox 'plugins\diffnav-smoke\lib'
foreach ($sandboxPlugin in @('goland-diff-navigation', 'git-diff-review')) {
    $oldSandboxPlugin = Join-Path $sandbox "plugins\$sandboxPlugin"
    if (Test-Path -LiteralPath $oldSandboxPlugin) {
        $resolvedSandboxPlugin = (Resolve-Path -LiteralPath $oldSandboxPlugin).Path
        $expectedSandboxPlugin = [IO.Path]::GetFullPath($oldSandboxPlugin)
        if ($resolvedSandboxPlugin -ne $expectedSandboxPlugin -or !$resolvedSandboxPlugin.StartsWith($root + '\build\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Unexpected sandbox plugin cleanup path.'
        }
        Remove-Item -LiteralPath $resolvedSandboxPlugin -Recurse -Force
    }
}
New-Item -ItemType Directory -Path $fixture,$options,$pluginLib,$testLib -Force | Out-Null
Copy-Item -LiteralPath "$root\build\local\git-diff-review\lib\git-diff-review-0.3.4.jar" -Destination $pluginLib
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
New-Item -ItemType Directory -Path "$fixture\added", "$fixture\single", "$fixture\policy", "$fixture\menuA", "$fixture\menuB" -Force | Out-Null
Set-Content -LiteralPath "$fixture\menuA\item.txt" -Encoding utf8 -Value "head`noldA"
Set-Content -LiteralPath "$fixture\menuB\item.txt" -Encoding utf8 -Value "head`noldB"
Set-Content -LiteralPath "$fixture\menu-rename-old.txt" -Encoding utf8 -Value "rename old`nanchor`ntail"
Set-Content -LiteralPath "$fixture\policy\a.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyA() int { return PolicyB() + PolicyC() }"
Set-Content -LiteralPath "$fixture\policy\b.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyB() int { return PolicyC() }"
Set-Content -LiteralPath "$fixture\policy\c.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyC() int { return 1 }"
Set-Content -LiteralPath "$fixture\added\existing.go" -Encoding utf8 -Value "package added`n`nfunc Existing() int { return 1 }"
Set-Content -LiteralPath "$fixture\go.mod" -Encoding utf8 -Value "module example.com/diffnavfixture`n`ngo 1.21.0"
Set-Content -LiteralPath "$fixture\main.go" -Encoding utf8 -Value "package main`n`nfunc main() { _ = B() }"
Set-Content -LiteralPath "$fixture\b.go" -Encoding utf8 -Value "package main`n`nfunc B() int { return C() }"
Set-Content -LiteralPath "$fixture\c.go" -Encoding utf8 -Value "package main`n`nfunc C() int { return Clean() }"
Set-Content -LiteralPath "$fixture\clean.go" -Encoding utf8 -Value "package main`n`nfunc Clean() int { return 1 }`nfunc UnchangedCaller() int { return B() }"
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`nfunc Partial() int { return B() }"
Set-Content -LiteralPath "$fixture\lines.txt" -Encoding utf8 -Value "head`nanchor`ntail"
Set-Content -LiteralPath "$fixture\menu.txt" -Encoding utf8 -Value "head`nanchor`ntail"
Set-Content -LiteralPath "$fixture\replace.txt" -Encoding utf8 -Value "oldOne`noldTwo`ntail"
Set-Content -LiteralPath "$fixture\deleted.txt" -Encoding utf8 -Value "delOne`ndelTwo"
& git -C $fixture init --initial-branch=main | Out-Null
& git -C $fixture -c user.name='Diff Navigation Test' -c user.email='test@example.invalid' add .
& git -C $fixture -c user.name='Diff Navigation Test' -c user.email='test@example.invalid' commit -qm 'test: sandbox baseline'
if ($LASTEXITCODE -ne 0) { throw 'Fixture baseline failed.' }
Set-Content -LiteralPath "$fixture\policy\a.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyA() int { return PolicyB() + PolicyC() + 1 }"
Set-Content -LiteralPath "$fixture\policy\b.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyB() int { return PolicyC() + 1 }"
& git -C $fixture add policy/b.go
Set-Content -LiteralPath "$fixture\policy\c.go" -Encoding utf8 -Value "package policy`n`nfunc PolicyC() int { return 2 }"
Set-Content -LiteralPath "$fixture\b.go" -Encoding utf8 -Value "package main`n`nfunc B() int { return C() + 1 }"
& git -C $fixture add b.go
Set-Content -LiteralPath "$fixture\b.go" -Encoding utf8 -Value "package main`n`nfunc B() int { return C() + 2 }"
Set-Content -LiteralPath "$fixture\c.go" -Encoding utf8 -Value "package main`n`nfunc C() int { return Clean() + 2 }"
Set-Content -LiteralPath "$fixture\main.go" -Encoding utf8 -Value "package main`n`nfunc main() { _ = B() + 3 }"
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`nfunc Partial() int { return B() + 1 }"
& git -C $fixture add partial.go
Set-Content -LiteralPath "$fixture\partial.go" -Encoding utf8 -Value "package main`n`n// worktree-only`nfunc Partial() int { return B() + 1 }"
Set-Content -LiteralPath "$fixture\lines.txt" -Encoding utf8 -Value "head`none`ntwo`nthree`nanchor`ntail"
Set-Content -LiteralPath "$fixture\menu.txt" -Encoding utf8 -Value "head`nmenuOne`nmenuTwo`nanchor`ntail"
Set-Content -LiteralPath "$fixture\menuA\item.txt" -Encoding utf8 -Value "head`nnewA"
Set-Content -LiteralPath "$fixture\menuB\item.txt" -Encoding utf8 -Value "head`nnewB"
Move-Item -LiteralPath "$fixture\menu-rename-old.txt" -Destination "$fixture\menu-renamed.txt"
& git -C $fixture add -- menu-rename-old.txt menu-renamed.txt
Set-Content -LiteralPath "$fixture\menu-renamed.txt" -Encoding utf8 -Value "rename old`nanchor`ntail`nlocal edit"
Set-Content -LiteralPath "$fixture\replace.txt" -Encoding utf8 -Value "newOneLong`nnewTwoLong`ntail"
[IO.File]::WriteAllText((Join-Path $fixture 'new-no-eol.txt'), 'newOnly', [Text.UTF8Encoding]::new($false))
Set-Content -LiteralPath "$fixture\added\existing.go" -Encoding utf8 -Value "package added`n`nfunc Existing() int { return AddedB() + 1 }"
Set-Content -LiteralPath "$fixture\added\a.go" -Encoding utf8 -Value "package added`n`nfunc AddedA() int { return AddedB() + AddedStaged() + AddedPartial() }"
Set-Content -LiteralPath "$fixture\added\b.go" -Encoding utf8 -Value "package added`n`nfunc AddedB() int { return Existing() }"
Set-Content -LiteralPath "$fixture\added\staged.go" -Encoding utf8 -Value "package added`n`nfunc AddedStaged() int { return AddedB() }"
Set-Content -LiteralPath "$fixture\added\partial_added.go" -Encoding utf8 -Value "package added`n`nfunc AddedPartial() int { return AddedB() }"
& git -C $fixture add added/staged.go added/partial_added.go
if ($LASTEXITCODE -ne 0) { throw 'Added-file staging fixture failed.' }
Set-Content -LiteralPath "$fixture\added\partial_added.go" -Encoding utf8 -Value "package added`n`n// local-only added file edit`nfunc AddedPartial() int { return AddedB() }"
Set-Content -LiteralPath "$fixture\single\only.go" -Encoding utf8 -Value "package single`n`nfunc Only() {}"
Remove-Item -LiteralPath (Join-Path $fixture 'deleted.txt')
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
    '-Dide.experimental.ui.onboarding=false'
    '-Dllm.show.ai.promotion.window.on.start=false'
)
Set-Content -LiteralPath $vmOptions -Encoding utf8 -Value ($originalOptions + $sandboxOptions)
$previous = $env:GOLAND_VM_OPTIONS
try {
    $env:GOLAND_VM_OPTIONS = $vmOptions
    Set-Content -LiteralPath "$sandbox\smoke-report.txt" -Encoding utf8 -Value ''
    $process = Start-Process -FilePath "$IdePath\bin\goland64.exe" -ArgumentList ('"' + $fixture + '"') -PassThru -WindowStyle Hidden
    Write-Output "Sandbox launcher PID: $($process.Id). Report: $sandbox\smoke-report.txt"
} finally { $env:GOLAND_VM_OPTIONS = $previous }
