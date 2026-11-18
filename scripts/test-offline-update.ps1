param([Parameter(Mandatory=$true)][string]$IdePath,[Parameter(Mandatory=$true)][string]$JdkPath)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/plugin-version.ps1"
$fixture=Join-Path $root ('build/offline-update-check-'+[guid]::NewGuid().ToString('N'))
$config=Join-Path $fixture 'config';$system=Join-Path $fixture 'system';$plugin=Join-Path $config 'plugins/git-diff-review'
New-Item -ItemType Directory -Path "$plugin/lib" -Force | Out-Null
$oldJar=Join-Path $plugin 'lib/old-version.jar';[IO.File]::WriteAllText($oldJar,'previous installed payload')
$oldHash=(Get-FileHash -LiteralPath $oldJar).Hash
$planPath=& "$PSScriptRoot/prepare-local-update.ps1" -IdePath $IdePath -JdkPath $JdkPath -ConfigPath $config -SystemPath $system -Archive "$root/build/local/git-diff-review-$pluginVersion.zip" -Version $pluginVersion -PrepareOnly -NoRelaunch
$plan=Get-Content -LiteralPath $planPath -Raw | ConvertFrom-Json
$plan.ideExecutable=Join-Path $fixture 'bin/goland64.exe' # Never observe or launch the daily IDE in this fixture.
$plan | ConvertTo-Json | Set-Content -LiteralPath $planPath -Encoding utf8
$worker=Join-Path (Split-Path -Parent $planPath) 'complete-local-update.ps1'
$pwsh=Join-Path $PSHOME 'pwsh.exe'
function Assert([bool]$condition,[string]$message){if(!$condition){throw $message};Write-Output "PASS $message"}
$queueFixture=Join-Path $fixture 'OfflineQueueFixture.java'
@'
import java.nio.file.*;
import java.util.*;
import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.ide.startup.StartupActionScriptManager.DeleteCommand;
import com.intellij.ide.startup.StartupActionScriptManager.UnzipCommand;
public class OfflineQueueFixture {
 public static void main(String[] a) throws Exception {
  Path script=Path.of(a[1]), plugins=Path.of(a[2]), unrelated=Path.of(a[4]);
  if(a[0].equals("seed")) {
   StartupActionScriptManager.saveActionScript(List.of(new DeleteCommand(unrelated),
    new DeleteCommand(plugins.resolve("git-diff-review")),new DeleteCommand(plugins.resolve("goland-diff-navigation")),
    new UnzipCommand(Path.of(a[3]),plugins)),script);
  } else {
   var commands=StartupActionScriptManager.loadActionScript(script);
   if(commands.size()!=1 || !commands.getFirst().toString().equals("delete["+unrelated+"]")) throw new AssertionError(commands.toString());
   if(!Files.exists(unrelated)) throw new AssertionError("Unrelated command was executed");
   System.out.println("PASS unrelated startup task preserved and never executed");
  }
 }
}
'@ | Set-Content -LiteralPath $queueFixture -Encoding utf8
& "$JdkPath/bin/javac.exe" -cp "$IdePath/lib/*" -d $fixture $queueFixture
if($LASTEXITCODE -ne 0){throw 'Queue fixture compilation failed.'}
$unrelated=Join-Path $fixture 'unrelated-plugin.txt';[IO.File]::WriteAllText($unrelated,'unrelated task')
& "$JdkPath/bin/java.exe" -cp "$fixture;$IdePath/lib/*" OfflineQueueFixture seed $plan.actionScript $plan.plugins $plan.archive $unrelated
if($LASTEXITCODE -ne 0){throw 'Queue fixture initialization failed.'}
$queueHash=(Get-FileHash -LiteralPath $plan.actionScript).Hash
$queueHeld=[IO.File]::Open($plan.actionScript,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
try { & "$JdkPath/bin/java.exe" -cp $plan.helperClasspath QueuePluginUpdate clear-own $plan.actionScript $plan.plugins *> (Join-Path $fixture 'unreadable-queue-output.txt');$queueExit=$LASTEXITCODE } finally {$queueHeld.Dispose()}
Assert ($queueExit -ne 0) 'unreadable startup queue stops cleanup instead of masquerading as an absent queue'
Assert ((Get-FileHash -LiteralPath $plan.actionScript).Hash -eq $queueHash) 'failed queue read preserves every queued command'
$held=[IO.File]::Open($oldJar,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
try { & $pwsh -NoProfile -File $worker -PlanPath $planPath -Once; $lockedExit=$LASTEXITCODE } finally {$held.Dispose()}
Assert ($lockedExit -eq 2) 'locked installed JAR returns waiting instead of deleting or installing'
Assert ((Get-FileHash -LiteralPath $oldJar).Hash -eq $oldHash) 'locked old payload remains byte-for-byte intact'
Assert (!(Test-Path -LiteralPath $plan.backup)) 'waiting state makes no backup move or queue mutation'
Assert ((Get-FileHash -LiteralPath $plan.actionScript).Hash -eq $queueHash) 'waiting keeps all original startup commands intact'
# Verification can read the payload, but a directory handle without FILE_SHARE_DELETE blocks promotion.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class DiffnavDirectoryLock {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
 public static extern SafeFileHandle CreateFile(string name,uint access,uint share,IntPtr security,uint mode,uint flags,IntPtr template);
}
'@
$heldDirectory=[DiffnavDirectoryLock]::CreateFile($plan.stagedPlugin,[uint32]2147483648,3,[IntPtr]::Zero,3,0x02000000,[IntPtr]::Zero)
if($heldDirectory.IsInvalid){throw 'Could not create isolated directory lock fixture.'}
try { & $pwsh -NoProfile -File $worker -PlanPath $planPath -Once *> (Join-Path $fixture 'rollback-output.txt');$failedExit=$LASTEXITCODE } finally {$heldDirectory.Dispose()}
Assert ($failedExit -ne 0) 'promotion failure is reported as failed'
Assert ((Get-FileHash -LiteralPath $oldJar).Hash -eq $oldHash) 'promotion failure restores the complete old directory'
Assert ((Get-Content -LiteralPath $plan.statusPath -Raw|ConvertFrom-Json).phase -eq 'failed_restored') 'failed update reports old directories restored rather than installed'
$plan.backup=Join-Path $config 'diffnav-update/backup-success';$plan|ConvertTo-Json|Set-Content -LiteralPath $planPath -Encoding utf8
& $pwsh -NoProfile -File $worker -PlanPath $planPath -Once
Assert ($LASTEXITCODE -eq 0) 'released lock permits an offline update'
Assert ((Get-Content -LiteralPath $plan.statusPath -Raw|ConvertFrom-Json).phase -eq 'installed') 'successful update reports installed'
Assert ((Get-FileHash -LiteralPath "$plugin/lib/git-diff-review-$pluginVersion.jar").Hash -eq $plan.jarHash) 'installed JAR matches the verified package'
Assert ((Get-FileHash -LiteralPath "$($plan.backup)/git-diff-review/lib/old-version.jar").Hash -eq $oldHash) 'old plugin remains recoverable outside plugin discovery'
Assert (@(Get-ChildItem -LiteralPath "$plugin/lib" -File).Count -eq 1) 'installation contains exactly one plugin version'
& "$JdkPath/bin/java.exe" -cp "$fixture;$IdePath/lib/*" OfflineQueueFixture verify $plan.actionScript $plan.plugins $plan.archive $unrelated
if($LASTEXITCODE -ne 0){throw 'Unrelated queue preservation failed.'}
. "$PSScriptRoot/offline-update-utils.ps1"
$recoveryRoot=Join-Path $fixture 'recovery-fixture'
$newPlugin=Join-Path $recoveryRoot 'plugins/git-diff-review'
$legacyOriginal=Join-Path $recoveryRoot 'plugins/goland-diff-navigation'
$oldBackup=Join-Path $recoveryRoot 'backup/git-diff-review'
$legacyBackup=Join-Path $recoveryRoot 'backup/goland-diff-navigation'
New-Item -ItemType Directory -Path $newPlugin,$oldBackup,$legacyBackup -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $newPlugin 'new.txt'),'new payload')
[IO.File]::WriteAllText((Join-Path $oldBackup 'old.txt'),'old payload')
[IO.File]::WriteAllText((Join-Path $legacyBackup 'legacy.txt'),'legacy payload')
$moves=@(@{original=$legacyOriginal;backup=$legacyBackup},@{original=$newPlugin;backup=$oldBackup})
$heldNew=[DiffnavDirectoryLock]::CreateFile($newPlugin,[uint32]2147483648,3,[IntPtr]::Zero,3,0x02000000,[IntPtr]::Zero)
if($heldNew.IsInvalid){throw 'Could not create recovery lock fixture.'}
try { $recovery=Restore-PluginDirectories $newPlugin (Join-Path $recoveryRoot 'failed-new') $true $moves } finally {$heldNew.Dispose()}
Assert ($recovery.newInstalled -and $recovery.pending.Count -eq 1) 'blocked new directory remains explicitly pending during recovery'
Assert (Test-Path -LiteralPath (Join-Path $legacyOriginal 'legacy.txt')) 'one blocked restore does not prevent another old directory from being restored'
$recovery=Restore-PluginDirectories $newPlugin (Join-Path $recoveryRoot 'failed-new') $recovery.newInstalled $recovery.pending
Assert (!$recovery.newInstalled -and $recovery.pending.Count -eq 0) 'recovery completes after its own lock is released'
Assert ((Get-Content -LiteralPath (Join-Path $newPlugin 'old.txt') -Raw) -eq 'old payload') 'recovery retry restores original contents without deleting the failed new payload'
Write-Output "Offline update checks complete. Artifacts: $fixture"
