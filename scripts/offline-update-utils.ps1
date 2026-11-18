# All recovery attempts are independent. A blocked directory remains pending, without hiding the initial failure.
function Restore-PluginDirectories([string]$Plugin,[string]$Quarantine,[bool]$NewInstalled,[object[]]$Moves) {
    $issues=[Collections.Generic.List[string]]::new()
    $pending=[Collections.Generic.List[object]]::new()
    if ($NewInstalled -and (Test-Path -LiteralPath $Plugin)) {
        try { [IO.Directory]::Move($Plugin,$Quarantine); $NewInstalled=$false }
        catch { $issues.Add("Could not move failed new plugin: $($_.Exception.Message)") }
    }
    for ($i=$Moves.Count-1; $i -ge 0; $i--) {
        $item=$Moves[$i]
        try {
            if (Test-Path -LiteralPath $item.original) { throw "Restore target still exists: $($item.original)" }
            [IO.Directory]::Move($item.backup,$item.original)
        } catch { $pending.Add($item);$issues.Add("Could not restore $($item.original): $($_.Exception.Message)") }
    }
    [pscustomobject]@{newInstalled=$NewInstalled;pending=@($pending.ToArray());errors=@($issues.ToArray())}
}
