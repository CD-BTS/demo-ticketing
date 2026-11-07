# Issue #16 Mock 정책 기반 차등 대기열

Demo Ticketing 기능을 구현한 것이며 실제 BTS 정책 결정 API 호출과 실제 CAPTCHA 서비스 연동은 포함하지 않는다. Mock CAPTCHA의 산술 문제는 **테스트·시연용이며 실제 봇 방지 기능이 아니다**. 기존 공유 데모 사용자 인증도 실사용 계정 소유권 증명이 아니다. 이 구성을 운영 보안 기능으로 사용하지 않는다.

## 설정

기본 `POLICY_DECISION_MODE=disabled`, `CAPTCHA_MODE=disabled`는 기존 API 호환을 위해 신규 사용자를 모두 ALLOW로 처리한다. Mock은 명시적으로 활성화해야 한다. `POLICY_DECISION_MODE=mock`일 때 `POLICY_MOCK_ACTIONS` JSON 객체가 인증된 사용자의 공개 ID를 action으로 매핑한다. 미설정 사용자는 ALLOW다. 요청으로 action/userId를 지정하거나 정책을 변경하는 공개 API는 없다.

```powershell
# 저장소 루트, 별도 PowerShell 터미널. 기존 서버와 .env는 유지한다.
$env:SERVER_PORT = '18087'
$env:QUEUE_KEY_PREFIX = 'demo-ticketing:queue:priority-manual'
$env:QUEUE_CAPACITY = '1'
$env:QUEUE_ADMIT_TTL = 'PT2M'
$env:POLICY_DECISION_MODE = 'mock'
$env:POLICY_MOCK_ACTIONS = '{"priority_captcha":"REQUIRE_CAPTCHA","priority_delay":"DELAY","priority_restrict":"RESTRICT"}'
$env:CAPTCHA_MODE = 'mock'
$env:BTS_ENABLED = 'false'
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Invoke-Backend.ps1 -Task Run
```

사용 가능한 정책 모드는 disabled/mock뿐이다. http 등 미구현 모드는 시작 시 오류로 거부하며 Mock으로 대체하지 않는다. 정책 Mock과 기존 BTS 이벤트 전송 설정은 독립이다. CAPTCHA 모드도 disabled/mock뿐이다. CAPTCHA가 비활성화되면 문제·증명 발급은 503이고, CAPTCHA가 필요한 신규 등록은 여전히 차단한다.

| 설정 | 기본값 | 의미 |
|---|---|---|
| CAPTCHA_CHALLENGE_TTL | PT2M | 문제 유효기간 |
| CAPTCHA_GRANT_TTL | PT2M | 인증 증명 유효기간 |
| CAPTCHA_MAX_ATTEMPTS | 5 | 문제당 최대 답 제출 횟수 |

JSON 설정에는 외부 따옴표를 추가하지 않는다. `.env`를 쓰는 경우 `POLICY_MOCK_ACTIONS={"priority_delay":"DELAY"}` 형식이다. 기존 실행기의 환경변수가 .env보다 우선한다. 테스트 전용 서버는 Ctrl+C로 종료하고 해당 터미널을 닫으면 임시 환경 설정도 사라진다.

## 등록 및 CAPTCHA API

모든 API는 기존 `Authorization: Bearer <token>` 인증과 공통 ApiResponse를 사용한다. 아래 경로 앞에는 `/api/v1/concerts/{concertId}/queue`가 붙는다.

| Method / suffix | 요청 | 정상 data |
|---|---|---|
| POST / | 기존 body 없음, 선택적 X-Captcha-Token 헤더 | 기존 status/position/expiresAt |
| POST /captcha-challenges | body 없음 | challengeId, question, expiresAt |
| POST /captcha-verifications | `{ "challengeId":"...", "answer":"..." }` | captchaToken, expiresAt |
| GET /me | 변경 없음 | 기존 status/position/expiresAt |
| DELETE /me | 변경 없음 | null |

신규 등록은 201, 활성 중복은 200이다. 두 CAPTCHA API는 200이다. CAPTCHA 미인증은 403/CAPTCHA_REQUIRED, 잘못된 답·만료·재사용·타 사용자/공연 증명은 403/CAPTCHA_INVALID, RESTRICT는 403/QUEUE_RESTRICTED다. ALLOW 사용자에게 문제 발급을 요청하면 400/INVALID_REQUEST다. Redis·정책 제공자 장애는 503/SERVICE_UNAVAILABLE로 처리한다.

CAPTCHA_REQUIRED는 대기열 상태가 아니다. 인증 전에는 해당 사용자의 waiting/admitted 멤버·deadline·sequence를 생성하지 않는다. `captchaVerified:true`, 클라이언트 action 등으로 통과시킬 수 없다. 정상 가입·인증 흐름은 다음과 같다.

```powershell
# 위 전용 서버가 준비된 뒤 다른 터미널에서 실행
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:18087/api/v1'
. .\backend\scripts\MySql-Tools.ps1
$concert = 'priority-sim-' + [guid]::NewGuid().ToString('N').Substring(0,12)
$null = Invoke-DemoMySql @"
INSERT INTO concerts(public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
VALUES('$concert','Priority simulator','Test','Test',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,0,'Issue16 fixture',0);
"@
$login = Invoke-RestMethod -Method Post -Uri "$api/users" -ContentType 'application/json' -Body '{"userId":"priority_delay"}'
$headers = @{Authorization="Bearer $($login.data.token)"}
$queue = "$api/concerts/$concert/queue"
try { Invoke-RestMethod -Method Post -Uri $queue -Headers $headers }
catch { $_.ErrorDetails.Message } # CAPTCHA_REQUIRED
$challenge = (Invoke-RestMethod -Method Post -Uri "$queue/captcha-challenges" -Headers $headers).data
$challenge | Format-List
$answer = Read-Host '표시된 Mock 산술 문제의 답'
$body = @{challengeId=$challenge.challengeId;answer=$answer} | ConvertTo-Json
$grant = (Invoke-RestMethod -Method Post -Uri "$queue/captcha-verifications" -Headers $headers -ContentType 'application/json' -Body $body).data
$joinHeaders = @{Authorization=$headers.Authorization;'X-Captcha-Token'=$grant.captchaToken}
Invoke-RestMethod -Method Post -Uri $queue -Headers $joinHeaders
# 일반 대기자가 없고 빈자리가 있으므로 DELAY여도 즉시 ADMITTED
Invoke-RestMethod -Method Get -Uri "$queue/me" -Headers $headers
Invoke-RestMethod -Method Post -Uri $queue -Headers $headers # 활성 중복, CAPTCHA 재요구 없음
Invoke-RestMethod -Method Delete -Uri "$queue/me" -Headers $headers
try { Invoke-RestMethod -Method Post -Uri $queue -Headers $joinHeaders }
catch { $_.ErrorDetails.Message } # 소비한 토큰: CAPTCHA_INVALID
```

공연과 데모 사용자·세션은 구분된 이름으로 남긴다. 다른 공연·사용자·Redis 키는 삭제하지 않는다. 이탈은 현재 사용자 멤버만 제거하고 남은 테스트 키는 유휴 TTL로 정리된다. 답·captchaToken·Bearer를 로그나 공유 자료에 기록하지 않는다.

## Redis 알고리즘과 동시성

공연별 기존 `QUEUE_KEY_PREFIX:{base64url(concertId)}:` 아래 `waiting`을 일반 ZSET으로 유지하고 `waiting-delayed`를 추가했다. 두 그룹은 같은 sequence, deadlines, admitted, expired를 사용한다. 같은 그룹에서는 등록 Lua 실행 순서의 FIFO이며, 일반 그룹을 먼저 채우고 남은 정원을 후순위 그룹으로 채운다. DELAY의 최소 대기 시간은 없다.

일반 position은 ZRANK+1, 후순위 position은 일반 대기자 수+후순위 ZRANK+1이다. 따라서 일반 사용자가 유입되면 후순위 순번은 늘어날 수 있다. GET 및 #17 입장 권한 검사도 동일 Lua status를 사용한다. Redis TIME 기반 만료·승격과 기존 TTL·정원·이탈·만료 기록 보존 정책은 유지한다.

활성 WAITING/ADMITTED 등록은 정책을 다시 조회하지 않고 반환한다. Lua도 등록 직전에 활성 멤버를 재확인하므로 정책 조회와 등록 사이에 같은 사용자가 먼저 등록돼도 그룹·sequence·deadline을 변경하지 않는다. 이탈·만료 후에는 새 정책 평가와 필요한 CAPTCHA를 다시 수행한다. 정책 장애 중 동시 등록이 완료됐다면 활성 결과를 재확인해 반환하며, 미등록 사용자에게는 우회 허용하지 않는다.

문제는 `captcha-challenge:<challengeId>` HASH, 증명은 `captcha-grant:<SHA-256(token)>` HASH에 저장한다. 둘 다 공연 hash tag를 공유하며 별도 TTL을 가진다. 문제 검증 Lua는 사용자·deadline·시도 횟수를 확인하고 성공한 문제를 소비하면서 단 하나의 증명을 발급한다. 등록 Lua는 증명의 사용자·action·deadline을 검증하고 대기열 삽입과 함께 소비한다. 다른 요청은 증명 TTL을 연장하지 않는다. Redis Cluster에서도 관련 KEYS가 같은 슬롯에 놓인다. DB 입장 기록·별도 MySQL 테이블은 추가하지 않는다.

기존 데이터의 waiting은 일반 그룹으로 유지된다. 구버전 Lua는 후순위 키를 모르므로 구버전·신버전 인스턴스 혼용 배포를 하지 않고 모든 인스턴스를 함께 전환해야 한다. 기존 Redis 데이터·다른 키의 초기화는 필요하지 않다.

## 기아 및 실제 연동 미완료 범위

일반 대기자가 계속 유입되면 후순위 사용자는 입장하지 못하고 대기 TTL이 만료될 수 있다. 이는 MVP의 엄격한 일반 우선 정책이며 자동 승격·할당량·최소 지연 시간은 없다. 테스트는 반복 일반 유입에도 후순위가 대기하다 만료되는 것을 검증한다. 후속 운영에서는 후순위 최대 대기 시간·만료 건수를 관측하고 별도 정책으로 공정성을 결정해야 한다.

실제 BTS `POST /api/v1/users/{userId}/policy-decisions`, `{context:QUEUE_JOIN}`, `data.action` 연동은 구현하지 않았다. `PolicyDecisionClient`를 실제 구현체로 교체할 때 저장된 BTS UUID와 서버 전용 X-API-Key를 사용해야 한다. 현재 UUID는 최초 Outbox 전송 시 매핑되므로 queue join 시 없을 수 있다. 실제 연동 단계에서 사전 매핑·매핑 실패 정책을 해결해야 하며 Demo 공개 ID를 BTS UUID처럼 전달하거나 Mock UUID를 DB에 저장하지 않는다. 기존 BtsClient/Outbox 동작은 변경하지 않았다.

실제 CAPTCHA 서비스도 `CaptchaVerifier` 구현체와 발급/검증 계약을 연결하는 후속 작업이다. 현재 Mock 문제 자체를 실서비스 인증 증명으로 인정해서는 안 된다.

## 자동화 검증

기존 데모 MySQL과 demo-ticketing-redis만 실행한 상태에서 저장소 루트에서 실행한다.

```powershell
Push-Location .\backend
try {
    . .\scripts\MySql-Tools.ps1
    Read-DemoEnvironment -Path .\.env
    $env:REDIS_HOST='localhost'; $env:REDIS_PORT='6380'; $env:REDIS_DATABASE='0'
    $env:REDIS_USERNAME=''; $env:REDIS_PASSWORD=''
    .\gradlew.bat test redisTest mysqlTest --no-daemon
} finally { Pop-Location }
```

PriorityQueueApiRedisTests는 MockMvc와 실제 Redis로 action별 처리, 그룹별 FIFO·전체 순번, 중복·정책 변경, 만료·재등록, CAPTCHA 위조·바인딩·시도 제한·만료, 동시 문제 검증·토큰 소비·중복 등록 및 기아를 검증한다. QueueRedisTests/QueueApiRedisTests 및 ReservationMySqlTests는 기존 #15/#17 기능의 회귀를 검증한다. 테스트는 임의 접두사만 정리하며 컨테이너를 중지하거나 전체 Redis를 비우지 않는다.

기존 `Test-RedisQueue.ps1`은 정책·CAPTCHA를 명시적으로 비활성화하여 FIFO 시나리오를 유지하고, 정리·결과 수집 대상에 waiting-delayed도 포함한다. 차등 정책을 검증하려면 위 통합 테스트를 사용한다.

2026-11-05 검증 결과: 일반 test 14개, redisTest 18개(차등 정책 12개 포함), mysqlTest 20개로 총 52개 통과, 실패·오류 0개다. `test redisTest mysqlTest` 전체 실행 후 추가한 혼합 동시 등록 검증을 포함해 redisTest를 다시 실행했다. 기존 예약 테스트의 과거 고정 시각은 현재 기준 제어 시각으로 변경하여 공유 DB의 다른 서버가 테스트 hold를 이미 만료된 것으로 처리할 여지를 제거했다. 별도로 `Test-RedisQueue.ps1 -AdmitTtlSeconds 20`을 실제 전용 Spring Boot 서버와 데모 Redis에서 실행해 PASS를 확인했다. 그 실행의 결과 파일은 `backend/build/queue-sim-e01891715722/`에 있으며 테스트 공연·사용자는 식별 가능한 이름으로 보존했다.
