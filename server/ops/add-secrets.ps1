# Run ONCE on Novica's laptop (Windows PowerShell 5.1+), from the repo root:
#   powershell -ExecutionPolicy Bypass -File server\ops\add-secrets.ps1
# Creates SSM /dev/dobarboiler-server/env (BOILER_API_TOKEN, DOBARBOILER_DB_PASSWORD,
# DOBARBOILER_RO_PASSWORD) and puts DOBARBOILER_RO_PASSWORD into /dev/observability/env.
# Safe to re-run: an existing /dev/dobarboiler-server/env is kept and its RO password reused.
# Prints key names only, never values.
$ErrorActionPreference = "Stop"
$AwsProfile = "novica-dev"
$ServerParam = "/dev/dobarboiler-server/env"
$ObsParam = "/dev/observability/env"

function New-Hex([int]$bytes) {
    $b = New-Object byte[] $bytes
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($b)
    ($b | ForEach-Object { $_.ToString("x2") }) -join ""
}

# Windows PowerShell 5.1 turns every stderr line of a native command into a terminating error under
# $ErrorActionPreference = "Stop" (even with 2>$null), so aws runs with "Continue" and its stderr is
# collected instead. Returns the exit code, stdout (may hold secret values, never printed) and stderr.
function Invoke-Aws([string[]]$awsArgs) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $out = & aws @awsArgs 2>&1 } finally { $ErrorActionPreference = $eap }
    $code = $LASTEXITCODE
    $err = @($out | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } |
        ForEach-Object { $_.ToString() }) -join "`n"
    $std = @($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) -join "`n"
    [pscustomobject]@{ Code = $code; Out = $std; Err = $err.Trim() }
}

function Get-Param([string]$name) {
    $r = Invoke-Aws @("--profile", $AwsProfile, "ssm", "get-parameter", "--name", $name,
                      "--with-decryption", "--query", "Parameter.Value", "--output", "text")
    if ($r.Code -eq 0) { return $r.Out }
    if ($r.Err -match "ParameterNotFound") { return $null }
    throw "get-parameter $name failed: $($r.Err)"
}

function Set-Param([string]$name, [string]$value, [bool]$overwrite) {
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $value, (New-Object System.Text.UTF8Encoding $false))
        $awsArgs = @("--profile", $AwsProfile, "ssm", "put-parameter", "--name", $name,
                     "--type", "SecureString", "--value", "file://$tmp")
        if ($overwrite) { $awsArgs += "--overwrite" }
        $r = Invoke-Aws $awsArgs
        if ($r.Code -ne 0) { throw "put-parameter $name failed: $($r.Err)" }
    } finally { Remove-Item $tmp -Force }
}

$existing = Get-Param $ServerParam
if ($existing) {
    $m = [regex]::Match($existing, "(?m)^DOBARBOILER_RO_PASSWORD=(.+)$")
    if (-not $m.Success) { throw "$ServerParam exists but has no DOBARBOILER_RO_PASSWORD" }
    $ro = $m.Groups[1].Value.Trim()
    Write-Host "$ServerParam already exists, kept"
} else {
    $ro = New-Hex 24
    $server = "BOILER_API_TOKEN=$(New-Hex 32)`nDOBARBOILER_DB_PASSWORD=$(New-Hex 24)`nDOBARBOILER_RO_PASSWORD=$ro`n"
    Set-Param $ServerParam $server $false
    Write-Host "${ServerParam}: BOILER_API_TOKEN, DOBARBOILER_DB_PASSWORD, DOBARBOILER_RO_PASSWORD"
}

$obs = Get-Param $ObsParam
if (-not $obs) { throw "cannot read $ObsParam" }
if ($obs -match "(?m)^DOBARBOILER_RO_PASSWORD=") {
    $obs = [regex]::Replace($obs, "(?m)^DOBARBOILER_RO_PASSWORD=.*$", "DOBARBOILER_RO_PASSWORD=$ro")
} else {
    $obs = $obs.TrimEnd() + "`nDOBARBOILER_RO_PASSWORD=$ro`n"
}
Set-Param $ObsParam $obs $true
Write-Host "${ObsParam}: DOBARBOILER_RO_PASSWORD set"
Write-Host "Done. Tell Claude on novica-dev to run: bash server/ops/go-live.sh"
