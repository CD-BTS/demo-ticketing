Set-StrictMode -Version Latest

function Invoke-DemoMySql {
    param([Parameter(Mandatory=$true)][string]$Sql)
    # Fixed demo-only target. No BTS container/admin password fallback.
    # UTF-8/LF stdin avoids Windows PowerShell native pipe encoding problems.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = 'docker'
    $info.Arguments = 'exec -i demo-ticketing-mysql sh -s'
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    $null = $process.Start()
    $outputTask = $process.StandardOutput.ReadToEndAsync()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $script = @'
set -eu
[ "$MYSQL_DATABASE" = "demo_ticketing_db" ] && [ "$MYSQL_USER" = "demo_user" ] || exit 64
export MYSQL_PWD="$MYSQL_PASSWORD"
exec mysql --user="$MYSQL_USER" --host=127.0.0.1 --database=demo_ticketing_db --default-character-set=utf8mb4 --batch --raw <<'DEMO_SQL_END'
'@
    $script = $script.Replace("`r", '') + "`n" +
        $Sql.Replace("`r", '').TrimEnd() + "`nDEMO_SQL_END`n"
    try {
        $inputBytes = [Text.Encoding]::UTF8.GetBytes($script)
        $process.StandardInput.BaseStream.Write($inputBytes, 0, $inputBytes.Length)
        $process.StandardInput.BaseStream.Flush()
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $outputTask.GetAwaiter().GetResult()
        $errorOutput = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $code = [regex]::Match($errorOutput, 'ERROR\s+\d+\s+\([A-Z0-9]+\)').Value
            throw ("Demo MySQL command failed (exit " + $process.ExitCode + "). " + $code +
                ' Check demo Compose/authentication/schema locally; SQL details withheld.')
        }
        return $output
    } finally { $process.Dispose() }
}

function Read-DemoEnvironment {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $Path) {
        if ($line -match '^(DB_HOST|DB_PORT|DB_USERNAME|DB_PASSWORD|SERVER_PORT|JPA_SHOW_SQL|HOLD_DURATION|TEST_PAYMENT_IDENTIFIERS|DEMO_JOBS_ENABLED|BTS_ENABLED|BTS_BASE_URL|BTS_SERVICE_ID|BTS_API_KEY|REDIS_HOST|REDIS_PORT|REDIS_USERNAME|REDIS_PASSWORD|REDIS_DATABASE|QUEUE_KEY_PREFIX|QUEUE_CAPACITY|QUEUE_WAIT_TTL|QUEUE_ADMIT_TTL|QUEUE_EXPIRED_RETENTION|POLICY_DECISION_MODE|POLICY_MOCK_ACTIONS|CAPTCHA_MODE|CAPTCHA_CHALLENGE_TTL|CAPTCHA_GRANT_TTL|CAPTCHA_MAX_ATTEMPTS)=(.*)$') {
            # Existing exported application variables take precedence.
            if (-not [Environment]::GetEnvironmentVariable($Matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            }
        }
    }
}
