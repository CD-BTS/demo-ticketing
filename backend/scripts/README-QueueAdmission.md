# Issue #17 입장 권한 검증

좌석 선점 서비스는 공연 잠금 획득 후 Redis Lua status 결과가 ADMITTED인지 확인한다. 모든 호출 경로에 적용되며 생성·교체·동일 선점 재시도도 권한이 필요하다. 실패 응답은 403/QUEUE_NOT_ADMITTED 또는 Redis 장애 시 503/SERVICE_UNAVAILABLE이다. 예매 확정은 유효한 본인 hold가 있으면 입장 권한 만료 후에도 허용한다. Lua와 DB 스키마는 변경하지 않는다.

## 자동화 테스트

저장소 루트 PowerShell에서 실행한다. 기존 데모 MySQL(3308), Redis(6380) 컨테이너가 실행 중이고 backend/.env가 준비되어 있어야 한다.

```powershell
Push-Location .\backend
try {
    . .\scripts\MySql-Tools.ps1
    Read-DemoEnvironment -Path .\.env
    $env:REDIS_HOST = 'localhost'
    $env:REDIS_PORT = '6380'
    $env:REDIS_DATABASE = '0'
    $env:REDIS_USERNAME = ''
    $env:REDIS_PASSWORD = ''
    .\gradlew.bat test redisTest mysqlTest --no-daemon
} finally { Pop-Location }
```

`ReservationMySqlTests`는 이제 실제 Redis와 MySQL을 함께 사용한다. 테스트 전용 접두사와 임의 공연·사용자로 수행하고 해당 테스트의 데이터만 정리한다. 기존 예약·동시성·멱등·Outbox 회귀 테스트도 실제 입장 등록을 수행한다. 만료된 admission 멤버는 Redis score를 과거 deadline으로 설정해 잔존 기록의 만료 판정을 검증한다. 기존 QueueRedisTests는 실제 시간 경과에 따른 TTL 및 FIFO 승격을 검증한다. Redis 장애 검증은 테스트의 QueueStore 호출만 닫힌 loopback 포트로 연결하여 실제 연결 예외→503 변환을 확인하며 실행 중인 데모 Redis를 정지하지 않는다.

## 직접 HTTP API 호출

먼저 별도 터미널에서 현재 코드의 전용 서버를 실행한다. 입장 TTL 20초, hold TTL 5분으로 입장 만료 후 확정을 확인하기 쉽게 한다. 아래 환경변수는 해당 터미널에만 적용되며 .env는 변경하지 않는다.

```powershell
$env:SERVER_PORT = '18086'
$env:QUEUE_KEY_PREFIX = 'demo-ticketing:queue:guard-manual'
$env:QUEUE_CAPACITY = '2'
$env:QUEUE_ADMIT_TTL = 'PT20S'
$env:HOLD_DURATION = 'PT5M'
$env:BTS_ENABLED = 'false'
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Invoke-Backend.ps1 -Task Run
```

다른 터미널에서 다음 블록을 실행한다. 테스트 전용 공연·좌석을 데모 DB에 추가하고 고유 사용자로 실제 API를 호출한다. 인증 토큰을 콘솔에 출력하지 않는다. 기존 공연·좌석에는 접근하지 않는다.

```powershell
$ErrorActionPreference = 'Stop'
. .\backend\scripts\MySql-Tools.ps1
$api = 'http://localhost:18086/api/v1'
$run = [guid]::NewGuid().ToString('N').Substring(0,12)
$concert = "guard-sim-$run"
$null = Invoke-DemoMySql @"
INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
VALUES('$concert','Admission guard simulator','Test','Test',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,1000,'Issue17 fixture',0);
INSERT INTO concert_seats(concert_id,seat_code,row_label,seat_number,grade,price,state)
SELECT id,'A-01','A',1,'VIP',1000,'AVAILABLE' FROM concerts WHERE public_id='$concert';
"@
$login = Invoke-RestMethod -Method Post -Uri "$api/users" -ContentType 'application/json' -Body (@{userId="guard_${run}"} | ConvertTo-Json)
$headers = @{Authorization="Bearer $($login.data.token)"}
$holdBody = @{seatIds=@('A-01')} | ConvertTo-Json

# 미등록 상태: 실제 403/QUEUE_NOT_ADMITTED 응답 출력
try {
    Invoke-RestMethod -Method Post -Uri "$api/concerts/$concert/seat-holds" -Headers $headers -ContentType 'application/json' -Body $holdBody
    throw 'Expected HTTP 403'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 403) { throw }
    Write-Host $_.Exception.Message
}

# 등록 후 서버의 실제 입장 상태 확인
$join = Invoke-RestMethod -Method Post -Uri "$api/concerts/$concert/queue" -Headers $headers
$join.data | Format-List
if ($join.data.status -ne 'ADMITTED') { throw 'Not admitted; query queue/me until admitted.' }
$hold = Invoke-RestMethod -Method Post -Uri "$api/concerts/$concert/seat-holds" -Headers $headers -ContentType 'application/json' -Body $holdBody
$hold.data | Format-List

# 같은 선점 재요청: holdId와 expiresAt 유지
$retry = Invoke-RestMethod -Method Post -Uri "$api/concerts/$concert/seat-holds" -Headers $headers -ContentType 'application/json' -Body $holdBody
$retry.data | Format-List

# 실제 입장 TTL 만료까지 기다림. 위 전용 서버는 20초, hold는 5분.
$admitDeadline = [DateTimeOffset]::Parse($join.data.expiresAt)
do {
    Start-Sleep -Milliseconds 200
    $status = Invoke-RestMethod -Method Get -Uri "$api/concerts/$concert/queue/me" -Headers $headers
} while ([DateTimeOffset]::UtcNow -le $admitDeadline.AddMilliseconds(100))
$status.data | Format-List

# hold가 아직 유효하면 입장 만료 후에도 확정 가능, 동일 요청은 기존 예매 반환
if ([DateTimeOffset]::UtcNow -ge [DateTimeOffset]::Parse($hold.data.expiresAt)) {
    throw 'Hold also expired. Use shorter QUEUE_ADMIT_TTL on a dedicated test server for this scenario.'
}
$bookBody = @{holdId=$hold.data.holdId;paymentIdentifier='test-pay-shared-01'} | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Uri "$api/bookings" -Headers $headers -ContentType 'application/json' -Body $bookBody
$booking.data | Format-List
Invoke-RestMethod -Method Post -Uri "$api/bookings" -Headers $headers -ContentType 'application/json' -Body $bookBody

# 본 테스트 예매 취소 및 대기열 이탈. 테스트 이력은 보존, 기존 데이터 삭제 없음.
Invoke-RestMethod -Method Post -Uri "$api/bookings/$($booking.data.id)/cancel" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "$api/concerts/$concert/queue/me" -Headers $headers
```

위 설정을 입력한 터미널은 테스트 후 닫으면 된다. 테스트 전용 공연·사용자·예매 이력은 식별 가능한 이름으로 남는다. 대기열은 DELETE로 본인 멤버를 제거하며 남은 테스트 공연 키에는 기존 유휴 TTL이 적용된다. 스크립트가 중간에 실패했다면 반환된 holdId로 `DELETE /api/v1/seat-holds/{holdId}`를 호출해 해제할 수 있다. 이 해제 요청에도 대기열 권한은 필요하지 않다.
