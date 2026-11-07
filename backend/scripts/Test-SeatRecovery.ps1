param([switch]$BaselineOnly)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$run=[Guid]::NewGuid().ToString('N').Substring(0,12)
$artifacts=Join-Path $root "build/seat-recovery-$run"
$null=New-Item -ItemType Directory -Path $artifacts -Force
$saved=@{}
Get-ChildItem Env: | ForEach-Object {$saved[$_.Name]=$_.Value}
$owned=New-Object System.Collections.Generic.List[object]

# 모든 Docker 명령은 이 실행에서 만든 컨테이너에만 접근한다.
function Docker([string[]]$Arguments){
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=(Get-Command docker.exe -CommandType Application).Source
    $info.Arguments=($Arguments | ForEach-Object {'"'+$_.Replace('"','\"')+'"'}) -join ' '
    $info.UseShellExecute=$false
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[Text.Encoding]::UTF8; $info.StandardErrorEncoding=[Text.Encoding]::UTF8
    $process=New-Object Diagnostics.Process
    $process.StartInfo=$info
    try{
        $null=$process.Start()
        $output=$process.StandardOutput.ReadToEndAsync(); $errors=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout=$output.GetAwaiter().GetResult(); $stderr=$errors.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw "격리 Docker 명령 실패: $($Arguments[0]) (exit=$($process.ExitCode)). 기존 DB에는 접근하지 않았습니다."}
        return $stdout.Trim()
    }finally{$process.Dispose()}
}
function Start-Isolated([string]$role,[string]$image,[string]$containerPort,[string[]]$Extra){
    $name="demo-seat25-$role-$run"
    $arguments=@('run','--detach','--rm','--name',$name,'--label',"demo-ticketing.issue=25",'--label',"demo-ticketing.run=$run",'--publish',"127.0.0.1::${containerPort}")+$Extra+@($image)
    $id=Docker $arguments
    $owned.Add([pscustomobject]@{Name=$name;Id=$id})
    $binding=Docker @('port',$name,"$containerPort/tcp")
    if($binding -notmatch '^127\.0\.0\.1:(\d+)$'){throw '격리 컨테이너가 localhost 전용 임의 포트를 사용하지 않습니다.'}
    return [pscustomobject]@{Name=$name;Id=$id;Port=$Matches[1]}
}
function Sql([string]$query){
    return Docker @('exec',$mysql.Name,'mysql','--user=root','--skip-password','--batch','--raw','--database=demo_ticketing_db',('--execute='+$query))
}
try{
    $password=[Guid]::NewGuid().ToString('N')
    $username="recovery_$run"
    $extra=@('--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=demo_ticketing_db','--env',"MYSQL_USER=$username",'--env',"MYSQL_PASSWORD=$password")
    $mounts=@{'schema.sql'='10-schema.sql';'seed.sql'='20-seed.sql';'02-booking-support.sql'='30-booking-support.sql';'03-seat-concurrency.sql'='40-seat-concurrency.sql'}
    foreach($file in $mounts.Keys){
        $source=(Resolve-Path -LiteralPath (Join-Path $root "db/$file")).Path
        $extra+=@('--mount',"type=bind,source=$source,target=/docker-entrypoint-initdb.d/$($mounts[$file]),readonly")
    }
    $mysql=Start-Isolated 'mysql' 'mysql:8.0' '3306' $extra
    $redis=Start-Isolated 'redis' 'redis:7.4-alpine' '6379' @()
    $ready=$false
    for($i=0;$i -lt 120;$i++){
        try{
            $logs=Docker @('logs',$mysql.Name)
            if($logs -match 'MySQL init process done'){
                $count=Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='demo_ticketing_db';"
                if($count.Split("`n")[-1].Trim() -eq '11'){$ready=$true;break}
            }
        }catch{if($i -eq 119){throw}}
        Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw '격리 MySQL 초기화 시간 초과'}
    if((Docker @('exec',$redis.Name,'redis-cli','PING')) -ne 'PONG'){throw '격리 Redis 시작 실패'}
    $settings=@{
        SEAT_RECOVERY_ISOLATED=$run;DB_HOST='127.0.0.1';DB_PORT=$mysql.Port;DB_USERNAME=$username;DB_PASSWORD=$password;
        SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$($mysql.Port)/demo_ticketing_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        SPRING_DATASOURCE_USERNAME=$username;SPRING_DATASOURCE_PASSWORD=$password;
        REDIS_HOST='127.0.0.1';REDIS_PORT=$redis.Port;REDIS_DATABASE='0';REDIS_USERNAME='';REDIS_PASSWORD='';
        SPRING_DATA_REDIS_HOST='127.0.0.1';SPRING_DATA_REDIS_PORT=$redis.Port;SPRING_DATA_REDIS_DATABASE='0';SPRING_DATA_REDIS_USERNAME='';SPRING_DATA_REDIS_PASSWORD='';
        HOLD_DURATION='PT5M';DEMO_JOBS_ENABLED='false';BTS_ENABLED='false';BTS_SERVICE_ID='seat-recovery';
        POLICY_DECISION_MODE='disabled';CAPTCHA_MODE='disabled';TEST_PAYMENT_IDENTIFIERS='test-pay-shared-01,test-pay-shared-02,test-pay-shared-03'
    }
    foreach($key in $settings.Keys){[Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')}
    $runtime=[ordered]@{run=$run;utc=[DateTime]::UtcNow.ToString('o');branch=(& git branch --show-current | Out-String).Trim();baselineOnly=[bool]$BaselineOnly;mysql=$mysql.Name;mysqlPort=$mysql.Port;redis=$redis.Name;redisPort=$redis.Port;database='demo_ticketing_db';holdDuration='PT5M'}
    $runtime | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $artifacts 'runtime.json')
    Sql "SELECT DATABASE() AS database_name, CURRENT_USER() AS database_user, VERSION() AS mysql_version; SHOW CREATE TABLE reservation_user_concert_guards;" | Set-Content -Encoding UTF8 (Join-Path $artifacts 'database.txt')
    $arguments=if($BaselineOnly){@('mysqlTest','--tests','com.bts.demoticketing.booking.ReservationMySqlTests','--tests','com.bts.demoticketing.booking.SeatConcurrencyMySqlTests','--tests','com.bts.demoticketing.concert.ConcertApiMySqlTests')}else{@('test','redisTest','mysqlTest')}
    $arguments+=@('--no-daemon','--rerun-tasks')
    Push-Location $root
    try{& .\gradlew.bat @arguments | Tee-Object -FilePath (Join-Path $artifacts 'gradle.log') | Out-Host; $gradleExit=$LASTEXITCODE}finally{Pop-Location}
    # 실패한 실행도 SQL 결과와 XML을 보존하고 컨테이너는 finally에서 정리한다.
    $snapshot=@'
SELECT 'concert_seats' AS table_name,s.id,s.seat_code,s.state,s.active_hold_id,s.active_booking_id,s.version FROM concert_seats s JOIN concerts c ON c.id=s.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'seat_holds' AS table_name,h.* FROM seat_holds h JOIN concerts c ON c.id=h.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'seat_hold_items' AS table_name,i.* FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id JOIN concerts c ON c.id=h.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'bookings' AS table_name,b.id,b.public_id,b.hold_id,b.status,b.cancelled_at FROM bookings b JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'booking_seats' AS table_name,i.* FROM booking_seats i JOIN bookings b ON b.id=i.booking_id JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'admission_tickets' AS table_name,t.* FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%';
SELECT 'outbox_events' AS table_name,e.aggregate_id,e.event_type,e.delivery_status FROM outbox_events e JOIN bookings b ON b.public_id=e.aggregate_id JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%';
'@
    Sql $snapshot | Set-Content -Encoding UTF8 (Join-Path $artifacts 'database-final.txt')
    $integrityRaw=Sql (Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'seat-recovery/verify.sql'))
    $integrityJson=$integrityRaw.Split("`n")[-1].Trim()
    $integrityJson | Set-Content -Encoding UTF8 (Join-Path $artifacts 'integrity.json')
    $integrity=$integrityJson | ConvertFrom-Json
    $tasks=if($BaselineOnly){@('mysqlTest')}else{@('test','redisTest','mysqlTest')}
    $summaries=@()
    foreach($task in $tasks){
        $destination=Join-Path $artifacts $task
        $null=New-Item -ItemType Directory -Path $destination -Force
        foreach($file in Get-ChildItem (Join-Path $root "build/test-results/$task") -Filter 'TEST-*.xml'){
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            [xml]$xml=Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
            $summaries+=[pscustomobject]@{task=$task;suite=$xml.testsuite.name;tests=[int]$xml.testsuite.tests;failures=[int]$xml.testsuite.failures;errors=[int]$xml.testsuite.errors;skipped=[int]$xml.testsuite.skipped}
        }
    }
    $summaries | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $artifacts 'results.json')
    $summaries | Format-Table -AutoSize | Out-Host
    if($gradleExit -ne 0){throw "테스트 실패: 종료 코드 $gradleExit. 결과: $artifacts"}
    if($integrity.violations -ne 0){throw "최종 DB 정합성 위반: $($integrity.violations)건"}
    if(-not $BaselineOnly){
        foreach($suite in @('com.bts.demoticketing.booking.SeatRecoveryMySqlTests','com.bts.demoticketing.booking.SeatRecoverySchedulerMySqlTests')){
            $matched=@($summaries | Where-Object suite -eq $suite)
            if($matched.Count -ne 1 -or $matched[0].tests -eq 0 -or $matched[0].skipped -ne 0){throw "신규 테스트 미실행 또는 스킵: $suite"}
        }
    }
    Write-Host "PASS: 격리 DB 좌석 복구 검증. 결과: $artifacts"
}finally{
    $cleanupFailures=@()
    foreach($container in $owned){
        try{
            if($container.Name -notmatch '^demo-seat25-(mysql|redis)-[a-f0-9]{12}$'){throw '정리 대상 이름 불일치'}
            $id=Docker @('inspect','--format={{.Id}}',$container.Name)
            if($id -ne $container.Id){throw '정리 대상 ID 불일치'}
            $null=Docker @('stop','--timeout','10',$container.Name)
        }catch{$cleanupFailures+=$container.Name}
    }
    foreach($item in @(Get-ChildItem Env:)){if(-not $saved.ContainsKey($item.Name)){[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}}
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
    Write-Host "결과 파일: $artifacts"
    if($cleanupFailures.Count -gt 0){throw ('격리 컨테이너 정리 실패: '+($cleanupFailures -join ', '))}
}
