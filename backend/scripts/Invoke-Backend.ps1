param([ValidateSet('Build','Run','MySqlTest','RedisTest')][string]$Task = 'Run')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MySql-Tools.ps1')
$backendRoot = Split-Path $PSScriptRoot
Read-DemoEnvironment -Path (Join-Path $backendRoot '.env')
Push-Location $backendRoot
try {
    switch ($Task) {
        'Build' { & .\gradlew.bat build --no-daemon }
        'Run' { & .\gradlew.bat bootRun --no-daemon }
        'MySqlTest' { & .\gradlew.bat mysqlTest --rerun-tasks --no-daemon }
        'RedisTest' { & .\gradlew.bat test redisTest --rerun-tasks --no-daemon }
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
