# Run ONCE on Novica's laptop (Windows PowerShell 5.1+), from the repo root:
#   powershell -ExecutionPolicy Bypass -File server\ops\add-secrets.ps1
# Creates SSM /dev/dobarboiler-server/env (BOILER_API_TOKEN, DOBARBOILER_DB_PASSWORD,
# DOBARBOILER_RO_PASSWORD) and appends DOBARBOILER_RO_PASSWORD to /dev/observability/env.
# Prints key names only, never values.
$ErrorActionPreference = "Stop"
$Profile = "novica-dev"

function New-Hex([int]$bytes) {
    $b = New-Object byte[] $bytes
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($b)
    ($b | ForEach-Object { $_.ToString("x2") }) -join ""
}

function Put-Param([string]$name, [string]$value, [bool]$overwrite) {
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $value, (New-Object System.Text.UTF8Encoding $false))
        $args = @("--profile", $Profile, "ssm", "put-parameter", "--name", $name, "--type", "SecureString",
                  "--value", "file://$tmp")
        if ($overwrite) { $args += "--overwrite" }
        aws @args | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "put-parameter $name failed" }
    } finally { Remove-Item $tmp -Force }
}

$ro = New-Hex 24
$server = "BOILER_API_TOKEN=$(New-Hex 32)`nDOBARBOILER_DB_PASSWORD=$(New-Hex 24)`nDOBARBOILER_RO_PASSWORD=$ro`n"
Put-Param "/dev/dobarboiler-server/env" $server $false
Write-Host "/dev/dobarboiler-server/env: BOILER_API_TOKEN, DOBARBOILER_DB_PASSWORD, DOBARBOILER_RO_PASSWORD"

$obs = aws --profile $Profile ssm get-parameter --name /dev/observability/env --with-decryption --query Parameter.Value --output text
if ($LASTEXITCODE -ne 0) { throw "get-parameter /dev/observability/env failed" }
$obs = ($obs -join "`n")
if ($obs -match "(?m)^DOBARBOILER_RO_PASSWORD=") {
    $obs = [regex]::Replace($obs, "(?m)^DOBARBOILER_RO_PASSWORD=.*$", "DOBARBOILER_RO_PASSWORD=$ro")
} else {
    $obs = $obs.TrimEnd() + "`nDOBARBOILER_RO_PASSWORD=$ro`n"
}
Put-Param "/dev/observability/env" $obs $true
Write-Host "/dev/observability/env: DOBARBOILER_RO_PASSWORD added"
Write-Host "Done. On novica-dev run: ssm-env dobarboiler-server; ssm-env observability /data/srv/observability/.env"
