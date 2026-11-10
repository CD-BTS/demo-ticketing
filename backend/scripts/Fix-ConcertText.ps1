# Repairs garbled concert text. Run from the repository root:
#   . ./backend/scripts/Fix-ConcertText.ps1
# ASCII-only on purpose: Windows PowerShell 5 reads BOM-less .ps1 as ANSI. The Korean text lives in the .sql file.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MySql-Tools.ps1')
$backendRoot = Split-Path $PSScriptRoot
$sql = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $backendRoot 'db/05-fix-concert-text.sql')
Write-Output (Invoke-DemoMySql -Sql $sql)
Write-Output 'Done. venue_first_bytes should start with EC98AC for c1 (UTF-8 for the Korean letter).'