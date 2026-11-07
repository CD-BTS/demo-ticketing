param(
    [ValidateRange(1024,65535)][int]$Port=18086,
    [ValidateSet('same','different','overlap','same-user','confirm')][string[]]$Modes=@('same','different','overlap','same-user','confirm'),
    [ValidateSet(2,10,100)][int[]]$UserCounts=@(2,10,100),
    [switch]$KeepFixtures
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'MySql-Tools.ps1')
$root=Split-Path $PSScriptRoot
$run=[Guid]::NewGuid().ToString('N').Substring(0,12)
$prefix="demo-ticketing:seat-k6:$run"
$artifacts=Join-Path $root "build/seat-concurrency-$run"
$null=New-Item -ItemType Directory -Path $artifacts -Force
$saved=@{}
Get-ChildItem Env: | ForEach-Object {$saved[$_.Name]=$_.Value}
$server=$null
$fixtures=New-Object System.Collections.Generic.List[object]
$results=New-Object System.Collections.Generic.List[object]
function Assert($condition,[string]$message){if(-not $condition){throw "검증 실패: $message"}}
function Query([string]$sql){return Invoke-DemoMySql -Sql $sql}
function Cleanup-Fixture($fixture){
    # 실행기가 생성한 정확한 ID만 사용하며 공용 시드/기존 사용자 데이터는 건드리지 않는다.
    $concert=$fixture.Concert
    $userPrefix=$fixture.UserPrefix
    Assert ($concert -match '^k23-[a-f0-9]{12}-(same|different|overlap|same-user|confirm)-(2|10|100)$') '정리 대상 공연 ID'
    Assert ($userPrefix -match '^k23_[a-f0-9]{12}_[sdouc](2|10|100)_$') '정리 대상 사용자 접두사'
    $null=Query @"
START TRANSACTION;
SET @c=(SELECT id FROM concerts WHERE public_id='$concert');
DELETE e FROM outbox_events e JOIN bookings b ON b.public_id=e.aggregate_id WHERE b.concert_id=@c;
UPDATE concert_seats SET state='AVAILABLE',active_hold_id=NULL,active_booking_id=NULL WHERE concert_id=@c;
DELETE t FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id WHERE b.concert_id=@c;
DELETE i FROM booking_seats i JOIN bookings b ON b.id=i.booking_id WHERE b.concert_id=@c;
DELETE FROM bookings WHERE concert_id=@c;
DELETE i FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id WHERE h.concert_id=@c;
DELETE FROM seat_holds WHERE concert_id=@c;
DELETE FROM concert_seats WHERE concert_id=@c;
DELETE FROM concerts WHERE id=@c;
DELETE s FROM demo_sessions s JOIN demo_users u ON u.id=s.user_id WHERE LEFT(u.public_user_id,LENGTH('$userPrefix'))='$userPrefix';
DELETE FROM demo_users WHERE LEFT(public_user_id,LENGTH('$userPrefix'))='$userPrefix';
COMMIT;
"@
    $tag=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($concert)).TrimEnd('=').Replace('+','-').Replace('/','_')
    $base=$prefix+':{'+$tag+'}:'
    $keys=@('waiting','waiting-delayed','deadlines','admitted','expired','sequence') | ForEach-Object {$base+$_}
    $null=& docker exec demo-ticketing-redis redis-cli DEL @keys
    Assert ($LASTEXITCODE -eq 0) '전용 Redis 키 정리'
}
try{
    $null=Get-Command k6 -ErrorAction Stop
    $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
    try{$listener.Start()}finally{$listener.Stop()}
    Read-DemoEnvironment -Path (Join-Path $root '.env')
    $settings=@{DB_HOST='localhost';DB_PORT='3308';DB_USERNAME='demo_user';SERVER_PORT="$Port";REDIS_HOST='localhost';REDIS_PORT='6380';REDIS_DATABASE='0';REDIS_USERNAME='';REDIS_PASSWORD='';QUEUE_KEY_PREFIX=$prefix;QUEUE_CAPACITY='150';QUEUE_ADMIT_TTL='PT30M';BTS_ENABLED='false';DEMO_JOBS_ENABLED='false';POLICY_DECISION_MODE='disabled';CAPTCHA_MODE='disabled';HOLD_DURATION='PT5M';TEST_PAYMENT_IDENTIFIERS='test-pay-shared-01'}
    foreach($key in $settings.Keys){[Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')}
    # 추가 DDL은 사용자가 명시적으로 적용해야 한다. 스크립트에서 기존 DB를 암묵적으로 변경하지 않는다.
    $ddl=Query "SELECT COUNT(*) AS n FROM information_schema.TABLES WHERE TABLE_SCHEMA='demo_ticketing_db' AND TABLE_NAME='reservation_user_concert_guards';"
    Assert ($ddl.Trim().Split("`n")[-1].Trim() -eq '1') 'db/03-seat-concurrency.sql 적용 필요'
    Push-Location $root
    try{& .\gradlew.bat bootJar --no-daemon | Out-Host; Assert ($LASTEXITCODE -eq 0) 'bootJar 빌드'}finally{Pop-Location}
    $jar=Get-ChildItem (Join-Path $root 'build/libs/*.jar') | Where-Object Name -NotLike '*-plain.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $server=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar',('"'+$jar.FullName+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $artifacts 'server.stdout.log') -RedirectStandardError (Join-Path $artifacts 'server.stderr.log')
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($server.HasExited){throw "전용 서버 종료: $artifacts"}
        try{$null=Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/v1/concerts" -TimeoutSec 2; $ready=$true;break}catch{Start-Sleep -Seconds 1}
    }
    Assert $ready '전용 서버 시작 시간 초과'
    $runtime=[ordered]@{utc=[DateTime]::UtcNow.ToString('o');serverPid=$server.Id;port=$Port;mysql='8.0';hikariMaximumPoolSize=10;tomcatDefaultMaxThreads=200;queueCapacity=150;k6Version=(& k6 version | Out-String).Trim();logicalProcessors=[Environment]::ProcessorCount;branch=(& git branch --show-current | Out-String).Trim()}
    $runtime | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $artifacts 'runtime.json')
    foreach($mode in $Modes){foreach($n in $UserCounts){
        $case="$run-$mode-$n"
        $concert="k23-$case"
        $modeCode=@{same='s';different='d';overlap='o';'same-user'='u';confirm='c'}[$mode]
        $fixture=[pscustomobject]@{Concert=$concert;UserPrefix="k23_${run}_${modeCode}${n}_"}
        $fixtures.Add($fixture)
        $sql="INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue) VALUES('$concert','좌석 동시성 k6','test','test',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,100,'test',0);"
        for($seat=1;$seat -le 100;$seat++){$sql+="INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state) SELECT id,'S$seat','A',$seat,'VIP',100,'AVAILABLE' FROM concerts WHERE public_id='$concert';"}
        $null=Query $sql
        $env:BASE_URL="http://127.0.0.1:$Port"
        $env:TEST_CONCERT=$concert; $env:TEST_RUN=$case; $env:TEST_MODE=$mode; $env:TEST_VUS="$n"
        $env:TEST_USER_PREFIX=$fixture.UserPrefix
        $summaryPath=Join-Path $artifacts "$mode-$n-summary.json"
        $env:SUMMARY_PATH=$summaryPath
        $timer=[Diagnostics.Stopwatch]::StartNew()
        $k6Script=Join-Path $PSScriptRoot 'seat-concurrency/seat-concurrency.js'
        $k6Process=Start-Process -FilePath (Get-Command k6).Source -ArgumentList @('run','--quiet',('"'+$k6Script+'"')) -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $artifacts "$mode-$n-k6.stdout.log") -RedirectStandardError (Join-Path $artifacts "$mode-$n-k6.stderr.log")
        $k6Exit=$k6Process.ExitCode; $timer.Stop()
        # k6가 실패/시간 초과하더라도 커밋된 DB 결과를 반드시 수집한다.
        $verify=(Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'seat-concurrency/verify.sql')).Replace('__CONCERT__',$concert)
        $raw=Query $verify
        $json=$raw.Trim().Split("`n")[-1].Trim()
        $json | Set-Content -Encoding UTF8 (Join-Path $artifacts "$mode-$n-db.json")
        $db=$json | ConvertFrom-Json
        $summary=if(Test-Path $summaryPath){Get-Content -Raw $summaryPath | ConvertFrom-Json}else{$null}
        function Metric([string]$name,[string]$field){
            if($summary -and $summary.metrics.PSObject.Properties.Name -contains $name){return $summary.metrics.$name.values.$field}
            return $null
        }
        $result=[pscustomobject]@{mode=$mode;users=$n;k6Exit=$k6Exit;elapsedMs=$timer.ElapsedMilliseconds;p95Ms=(Metric 'race_duration' 'p(95)');created=(Metric 'race_created' 'count');conflicts=(Metric 'race_conflicts' 'count');reused=(Metric 'race_reused' 'count');held=$db.held;booked=$db.booked;activeHolds=$db.activeHolds;bookings=$db.bookings;tickets=$db.tickets;outbox=$db.outbox;violations=$db.violations}
        $results.Add($result)
        $results | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $artifacts 'results.json')
        $result | Format-Table -AutoSize | Out-Host
        Assert ($k6Exit -eq 0) "k6 $mode/$n 임계값"
        Assert ($db.violations -eq 0) "DB $mode/$n 정합성"
        if($mode -eq 'confirm'){
            Assert ($db.booked -eq 1 -and $db.held -eq 0 -and $db.activeHolds -eq 0 -and $db.bookings -eq 1 -and $db.tickets -eq 1 -and $db.outbox -eq 1) '확정 DB 결과'
        }else{
            $expected=if($mode -eq 'different'){$n}elseif($mode -eq 'overlap'){2}else{1}
            $expectedHolds=if($mode -eq 'different'){$n}else{1}
            Assert ($db.held -eq $expected -and $db.activeHolds -eq $expectedHolds -and $db.booked -eq 0 -and $db.bookings -eq 0 -and $db.tickets -eq 0 -and $db.outbox -eq 0) '선점 DB 결과'
        }
    }}
    Write-Host "PASS: $($results.Count)개 k6 시나리오 및 DB 정합성 검증. 결과: $artifacts"
}finally{
    if($server -and -not $server.HasExited){Stop-Process -Id $server.Id}
    if(-not $KeepFixtures){foreach($fixture in $fixtures){Cleanup-Fixture $fixture}}
    foreach($item in @(Get-ChildItem Env:)){if(-not $saved.ContainsKey($item.Name)){[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}}
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
    Write-Host "결과 파일: $artifacts"
}
