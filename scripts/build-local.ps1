param(
    [Parameter(Mandatory = $true)][string]$IdePath,
    [string]$JdkPath = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!$JdkPath -or !(Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw 'Specify -JdkPath (JDK 25 or newer).' }
if (!(Test-Path -LiteralPath "$IdePath\product-info.json")) { throw 'IdePath must be a JetBrains IDE installation.' }
$product = Get-Content -LiteralPath "$IdePath\product-info.json" -Raw | ConvertFrom-Json
if ($product.buildNumber -notlike '262.*') { throw 'This compatibility adapter currently targets IDE build 262 only.' }
if (!(Test-Path -LiteralPath "$IdePath\plugins\vcs-git\lib")) { throw 'The IDE must include the Git4Idea plugin.' }
$output = Join-Path $root 'build\local'
if (Test-Path -LiteralPath $output) {
    $resolved = (Resolve-Path -LiteralPath $output).Path
    if (!$resolved.StartsWith($root + '\build\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected build output path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$classes = Join-Path $output 'classes'
$package = Join-Path $output 'git-diff-review\lib'
New-Item -ItemType Directory -Path $classes, $package -Force | Out-Null
$cp = "$IdePath\lib\*;$IdePath\plugins\vcs-git\lib\*;$IdePath\plugins\vcs-git\lib\modules\*"
$sources = @(Get-ChildItem -LiteralPath "$root\src\main\java" -Filter '*.java' -Recurse | ForEach-Object FullName)
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed.' }
$tests = @(Get-ChildItem -LiteralPath "$root\src\test\java" -Filter '*.java' -Recurse | ForEach-Object FullName)
$testClasses = Join-Path $output 'test-classes'
& "$JdkPath\bin\javac.exe" -encoding UTF-8 -cp "$classes;$cp" -d $testClasses @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& "$JdkPath\bin\java.exe" -cp "$testClasses;$classes;$cp" io.github.youjiaxing.diffnav.ReviewHistoryTest
if ($LASTEXITCODE -ne 0) { throw 'Regression tests failed.' }
foreach ($testClass in @('SelectedLinesTest', 'GitIndexTransactionTest')) {
    & "$JdkPath\bin\java.exe" -cp "$testClasses;$classes;$cp" "io.github.youjiaxing.diffnav.$testClass"
    if ($LASTEXITCODE -ne 0) { throw "$testClass failed." }
}
$jar = Join-Path $package 'git-diff-review-0.3.3.jar'
& "$JdkPath\bin\jar.exe" --create --file $jar -C $classes . -C "$root\src\main\resources" .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
$zip = Join-Path $output 'git-diff-review-0.3.3.zip'
Compress-Archive -LiteralPath (Join-Path $output 'git-diff-review') -DestinationPath $zip -Force
Write-Output "Built for $($product.name) $($product.version) ($($product.buildNumber)): $zip"
