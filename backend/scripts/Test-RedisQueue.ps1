param(
    [switch]$Interactive,
    [ValidateRange(20,600)][int]$AdmitTtlSeconds = 60,
    [ValidateRange(1024,65535)][int]$Port = 18085,
    [switch]$KeepQueueKeys
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'MySql-Tools.ps1')
$root = Split-Path $PSScriptRoot
$run = [guid]::NewGuid().ToString('N').Substring(0,12)
$concert = "fifo-sim-$run"
$prefix = "demo-ticketing:queue-sim:$run"
$tag = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($concert)).TrimEnd('=').Replace('+','-').Replace('/','_')
$base = $prefix + ':{' + $tag + '}:'
$keys = @('waiting','deadlines','admitted','expired','sequence','waiting-delayed') | ForEach-Object { $base + $_ }
$users = @()
$server = $null
$saved = @{}
Get-ChildItem Env: | ForEach-Object { $saved[$_.Name] = $_.Value }
$logDir = Join-Path $root "build/queue-sim-$run"
$null = New-Item -ItemType Directory -Path $logDir -Force

function Assert($condition, [string]$message) {
    if (-not $condition) { throw "FAIL: $message" }
}
function Redis([string[]]$Command) {
    $out = @(& docker exec demo-ticketing-redis redis-cli --raw @Command)
    if ($LASTEXITCODE -ne 0 -or ($out.Count -gt 0 -and "$($out[0])" -match '^(ERR|WRONGTYPE|NOAUTH)')) { throw 'Demo Redis command failed.' }
    return ,$out
}
function Api([string]$Method, [string]$Path, $User = $null, $Body = $null) {
    $args = @{Method=$Method; Uri="http://127.0.0.1:$Port/api/v1$Path"; TimeoutSec=10; UseBasicParsing=$true}
    if ($null -ne $User) { $args.Headers = @{Authorization="Bearer $($User.Token)"} }
    if ($null -ne $Body) { $args.Body = ($Body | ConvertTo-Json -Compress); $args.ContentType='application/json' }
    $response = Invoke-WebRequest @args
    $envelope = $response.Content | ConvertFrom-Json
    Assert ($envelope.success -eq $true) "API $Method $Path failed"
    $expected = if ($response.StatusCode -eq 201) { 'COMMON_201' } else { 'COMMON_200' }
    Assert ($envelope.code -eq $expected) 'Response envelope code'
    # Tokens are deliberately excluded from artifacts.
    if ($Path -notmatch '^/users$') {
        Add-Content -Encoding UTF8 -Path (Join-Path $logDir 'http.jsonl') -Value (@{at=[DateTime]::UtcNow.ToString('o');method=$Method;path=$Path;user=$(if($User){$User.Id}else{$null});http=[int]$response.StatusCode;response=$envelope} | ConvertTo-Json -Compress -Depth 8)
    }
    return $envelope.data
}
function Pause-Step {
    if ($Interactive) { $null = Read-Host 'Enter for next step (TTL continues while paused)' }
}
function Snapshot([string]$Event) {
    $rows = @(foreach ($u in $users) {
        $s = Api GET "/concerts/$concert/queue/me" $u
        $n = [int](Redis @('ZCARD',($base+'admitted')))[0]
        Assert ($n -le 2) 'Redis admitted count exceeds capacity 2'
        [pscustomobject]@{UserId=$u.Id;RedisId=$u.DbId;Status=$s.status;Position=$s.position;Admitted=$n;Event=$Event;ExpiresAt=$s.expiresAt}
    })
    Write-Host "`n[$([DateTime]::Now.ToString('HH:mm:ss'))] $Event" -ForegroundColor Cyan
    $rows | Format-Table UserId,RedisId,Status,Position,Admitted,Event -AutoSize | Out-Host
    $rows | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $logDir 'latest-api.json')
    return $rows
}
try {
    foreach ($name in @('demo-ticketing-redis','demo-ticketing-mysql')) {
        $state = & docker inspect --format '{{.State.Running}}' $name
        if ($LASTEXITCODE -ne 0) { throw "Required existing container missing: $name" }
        if ($state -ne 'true') { & docker start $name | Out-Host; Assert ($LASTEXITCODE -eq 0) "Start $name" }
    }
    $mapping = & docker port demo-ticketing-redis 6379/tcp
    Assert ($mapping -contains '127.0.0.1:6380') 'Demo Redis must expose localhost:6380'
    Assert ((Redis @('PING'))[0] -eq 'PONG') 'Redis PING'
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start() } finally { $listener.Stop() }
    Read-DemoEnvironment -Path (Join-Path $root '.env')
    $settings = @{DB_HOST='localhost';DB_PORT='3308';DB_USERNAME='demo_user';SERVER_PORT="$Port";REDIS_HOST='localhost';REDIS_PORT='6380';REDIS_DATABASE='0';REDIS_USERNAME='';REDIS_PASSWORD='';QUEUE_KEY_PREFIX=$prefix;QUEUE_CAPACITY='2';QUEUE_WAIT_TTL='PT1H';QUEUE_ADMIT_TTL="PT$($AdmitTtlSeconds)S";QUEUE_EXPIRED_RETENTION='PT1H';BTS_ENABLED='false';DEMO_JOBS_ENABLED='false'}
    foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key,$settings[$key],'Process') }
    $env:POLICY_DECISION_MODE = 'disabled'
    $env:CAPTCHA_MODE = 'disabled'
    Push-Location $root
    try { & .\gradlew.bat bootJar --no-daemon | Out-Host; Assert ($LASTEXITCODE -eq 0) 'bootJar build' } finally { Pop-Location }
    $jar = Get-ChildItem (Join-Path $root 'build/libs/*.jar') | Where-Object Name -NotLike '*-plain.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $server = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar',('"'+$jar.FullName+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir 'server.stdout.log') -RedirectStandardError (Join-Path $logDir 'server.stderr.log')
    $ready = $false
    for ($i=0; $i -lt 90; $i++) {
        if ($server.HasExited) { throw "Simulator server exited. See $logDir" }
        try { $null = Api GET '/concerts'; $ready=$true; break } catch { Start-Sleep -Seconds 1 }
    }
    Assert $ready 'Server startup timeout'
    # There is no concert creation API. Insert only a unique simulator fixture.
    $null = Invoke-DemoMySql "INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue) VALUES('$concert','FIFO simulator $run','Simulator','Test only',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,0,'Queue simulator fixture',0);"
    for ($i=1; $i -le 10; $i++) {
        $id = 'qs_' + $run + '_' + $i.ToString('00')
        $login = Api POST '/users' $null @{userId=$id}
        $db = (Invoke-DemoMySql "SELECT id FROM demo_users WHERE public_user_id='$id';").Trim().Split("`n")[-1].Trim()
        $users += [pscustomobject]@{Id=$login.userId;Token=$login.token;DbId=$db}
    }
    Write-Host "Dedicated server PID=$($server.Id), port=$Port, capacity=2, admit TTL=${AdmitTtlSeconds}s"
    Write-Host "Concert=$concert; Redis prefix=$prefix; artifacts=$logDir"
    Pause-Step
    foreach ($u in $users) {
        $s = Api POST "/concerts/$concert/queue" $u
        $n = [int](Redis @('ZCARD',($base+'admitted')))[0]
        Assert ($n -le 2) 'Capacity after registration'
        [pscustomobject]@{UserId=$u.Id;Status=$s.status;Position=$s.position;Admitted=$n;Event='REGISTER'} | Format-Table -AutoSize | Out-Host
    }
    $initial = Snapshot 'REGISTERED'
    for ($i=0; $i -lt 10; $i++) {
        if ($i -lt 2) { Assert ($initial[$i].Status -eq 'ADMITTED') 'First two admitted' }
        else { Assert ($initial[$i].Status -eq 'WAITING' -and $initial[$i].Position -eq ($i-1)) 'FIFO initial order' }
    }
    $before = $initial[7]
    $duplicate = Api POST "/concerts/$concert/queue" $users[7]
    Assert ($duplicate.position -eq $before.Position -and $duplicate.expiresAt -eq $before.ExpiresAt) 'Duplicate preserves rank and expiry'
    $null = Snapshot 'DUPLICATE'
    Pause-Step
    $null = Api DELETE "/concerts/$concert/queue/me" $users[4]
    $left = Snapshot 'LEAVE user05'
    Assert ($left[4].Status -eq 'NOT_JOINED') 'Waiting user left'
    for ($i=5; $i -lt 10; $i++) { Assert ($left[$i].Position -eq ($i-2)) 'Ranks shift after leave' }
    Write-Host 'Polling real API every 2 seconds until original admissions expire. API requests trigger FIFO promotion.'
    $deadline = [DateTime]::UtcNow.AddSeconds($AdmitTtlSeconds+15)
    do {
        Start-Sleep -Seconds 2
        $last = Snapshot 'TTL POLL / PROMOTION'
        if ($last[0].Status -eq 'EXPIRED' -and $last[1].Status -eq 'EXPIRED') { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert ($last[0].Status -eq 'EXPIRED' -and $last[1].Status -eq 'EXPIRED') 'Admission TTL expiry'
    Assert ($last[2].Status -eq 'ADMITTED' -and $last[3].Status -eq 'ADMITTED') 'Next FIFO users admitted'
    # Read Redis without mutations; compare members, ranks and exact expiry milliseconds.
    $waiting = @((Redis @('ZRANGE',($base+'waiting'),'0','-1')) | Where-Object { "$_" -ne '' })
    $admitted = @((Redis @('ZRANGE',($base+'admitted'),'0','-1')) | Where-Object { "$_" -ne '' })
    $expired = @((Redis @('ZRANGE',($base+'expired'),'0','-1')) | Where-Object { "$_" -ne '' })
    foreach ($row in $last) {
        $id = "$($row.RedisId)"
        switch ($row.Status) {
            'WAITING' { Assert (($waiting -contains $id) -and ([array]::IndexOf($waiting,$id)+1 -eq $row.Position)) 'Redis waiting rank' }
            'ADMITTED' { Assert ($admitted -contains $id) 'Redis admitted membership' }
            'EXPIRED' { Assert ($expired -contains $id) 'Redis expired membership' }
            'NOT_JOINED' { Assert (($waiting -notcontains $id) -and ($admitted -notcontains $id) -and ($expired -notcontains $id)) 'Redis absent member' }
        }
        if ($row.Status -in @('WAITING','ADMITTED')) {
            $suffix = if($row.Status -eq 'WAITING'){'deadlines'}else{'admitted'}
            $score = (Redis @('ZSCORE',($base+$suffix),$id))[0]
            Assert ([DateTimeOffset]::Parse($row.ExpiresAt).ToUnixTimeMilliseconds() -eq [long]$score) 'Redis/API expiry matches'
        }
    }
    Assert ($waiting.Count -eq 5 -and $admitted.Count -eq 2 -and $expired.Count -eq 2) 'Final Redis counts'
    $raw = foreach ($key in $keys) {
        $value = if ($key.EndsWith(':sequence')) { @(Redis @('GET',$key)) } else { @(Redis @('ZRANGE',$key,'0','-1','WITHSCORES')) }
        [pscustomobject]@{key=$key;values=$value}
    }
    $raw | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $logDir 'redis-final.json')
    $raw | Format-List | Out-Host
    Write-Host 'PASS: 10 users, FIFO, duplicate, leave, TTL promotion, capacity and Redis/API consistency.' -ForegroundColor Green
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id }
    if (-not $KeepQueueKeys) {
        try { $null = Redis (@('DEL') + $keys) }
        catch { Write-Warning "Could not clean simulator keys: $base* ($($_.Exception.Message))" }
    }
    # Unique SQL fixtures remain explicitly labeled; never delete users/bookings.
    foreach ($item in @(Get-ChildItem Env:)) { if (-not $saved.ContainsKey($item.Name)) { [Environment]::SetEnvironmentVariable($item.Name,$null,'Process') } }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    Write-Host "Artifacts: $logDir. Test-only SQL fixtures retained: $concert / qs_${run}_*."
}
