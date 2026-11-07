param([switch]$Apply)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MySql-Tools.ps1')
$backendRoot = Split-Path $PSScriptRoot

# Compose creates the database/account and initializes an empty volume.
# Explicit reapply never drops/truncates data or changes credentials.
Write-Output (Invoke-DemoMySql -Sql @'
SELECT DATABASE() AS active_database, CURRENT_USER() AS authenticated_user, VERSION() AS mysql_version;
SELECT TABLE_NAME,TABLE_ROWS AS estimated_rows FROM information_schema.TABLES WHERE TABLE_SCHEMA='demo_ticketing_db';
'@)
if ($Apply) {
    foreach ($file in @('schema.sql','seed.sql','02-booking-support.sql','03-seat-concurrency.sql')) {
        $sqlPath = Join-Path $backendRoot ('db/' + $file)
        $null = Invoke-DemoMySql -Sql (Get-Content -Raw -Encoding UTF8 -LiteralPath $sqlPath)
        Write-Output ("Applied demo-only SQL: " + $file)
    }
}
Write-Output (Invoke-DemoMySql -Sql (Get-Content -Raw -Encoding UTF8 (Join-Path $backendRoot 'db/verify.sql')))
