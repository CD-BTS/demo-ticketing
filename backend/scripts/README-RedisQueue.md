# Redis FIFO 실시간 시뮬레이터

저장소 루트의 VS Code PowerShell 터미널에서 실행합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Test-RedisQueue.ps1
```

Enter로 단계를 진행하려면 다음 명령을 사용합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\scripts\Test-RedisQueue.ps1 -Interactive
```

Docker Desktop, Java 17, `backend/.env`의 데모 MySQL 접속 비밀번호가 필요합니다. 기존 `demo-ticketing-mysql`과 `demo-ticketing-redis`만 확인하고, 정지 상태이면 시작합니다. 컨테이너가 없으면 오류로 종료합니다. Redis 포트는 `127.0.0.1:6380`이어야 합니다. 다른 프로젝트 컨테이너에는 접근하지 않습니다.

스크립트는 현재 소스로 bootJar를 빌드하고 **18085 포트에 실제 Spring Boot 서버**를 별도로 실행합니다. 기존 서버는 유지합니다. 포트가 사용 중이면 종료하며 `-Port 18086`으로 변경할 수 있습니다. 전용 인스턴스에만 정원 2명, 입장 TTL 60초, 대기 TTL 및 만료 기록 보존 1시간을 설정합니다. `.env`와 application.properties를 변경하지 않고 호출한 프로세스의 환경변수도 복원합니다. BTS 전송과 업무 스케줄러는 전용 서버에서 비활성화됩니다.

매 실행마다 `fifo-sim-<실행ID>` 공연과 `qs_<실행ID>_01`~`10` 사용자를 생성합니다. 공연 생성 API가 없어 공연만 데모 MySQL에 INSERT하며, 사용자 생성·등록·조회·이탈은 API 명세의 실제 HTTP/Bearer 인증으로 수행합니다. 사용자 세션 토큰은 출력하거나 결과 파일에 저장하지 않습니다.

표의 Status/Position은 API 응답, Admitted는 데모 Redis의 실제 ZCARD, RedisId는 데모 DB의 실제 내부 ID입니다. Event는 실행한 동작의 설명입니다. 사용자별 HTTP 조회는 순차적이므로 표 전체가 하나의 원자적 시점은 아닙니다. 2초 간격은 각 표 조회 소요 시간에 더해집니다. 매 등록과 각 사용자 조회 뒤 Redis 입장 인원을 확인하여 2명 초과 시 실패합니다. 이는 시뮬레이션에서 관측한 결과의 검증이며 동시 부하 테스트는 아닙니다.

순서대로 10명을 등록하고 최초 2명 입장, 8명 FIFO 대기, 8번 사용자 중복 등록의 순번·만료 시각 유지, 5번 사용자 이탈과 후속 순번 이동을 검증합니다. 이후 GET 폴링으로 1·2번 만료와 3·4번 입장을 관찰합니다. 이 구현의 만료 정리·승격은 요청 시 실행됩니다. 최종 API 상태를 Redis waiting/admitted/expired 멤버, 순번, 정확한 만료 시각과 비교합니다. 성공 시 `PASS`를 출력하고 실패 시 예외와 0이 아닌 종료 코드를 반환합니다.

대화형 모드에서도 **TTL은 계속 흐릅니다**. 등록 후 Enter 대기는 기본 60초 이내로 진행하세요. 긴 관찰이 필요하면 `-AdmitTtlSeconds 180`을 추가합니다. 이미 만료되면 예상 단계 검증이 실패할 수 있으므로 다시 실행하세요. 빠른 자동 검증에는 `-AdmitTtlSeconds 20`을 사용할 수 있습니다.

결과는 `backend/build/queue-sim-<실행ID>/`에 남습니다.

- `http.jsonl`: 사용자 인증 토큰을 제외한 실제 HTTP 상태 코드와 응답
- `latest-api.json`: 마지막 표의 실제 API 조회값
- `redis-final.json`: 최종 Redis 5개 키의 실제 값과 score
- `server.stdout.log`, `server.stderr.log`: 전용 서버 로그

종료 시 스크립트가 시작한 Java 프로세스와 **해당 실행의 정확한 Redis 키 5개만** 정리합니다. 기존 Redis 키와 기존 사용자·예매 데이터는 삭제하지 않습니다. 강제 프로세스 종료나 터미널 강제 종료 시 finally 정리는 보장되지 않으며, 테스트 키에는 유휴 TTL이 적용됩니다. 테스트 공연·사용자·세션은 구분된 이름으로 DB에 남깁니다.

Redis 결과를 직접 더 살펴보려면 `-KeepQueueKeys`를 추가하세요. 출력된 접두사와 공연의 키는 유휴 TTL 동안 보존됩니다. 수동 정리는 출력된 정확한 실행 ID를 사용해 해당 5개 키만 지정합니다. 아래는 예시이며 실제 출력된 ID로 교체해야 합니다.

```powershell
$runId = '실제출력된12자리실행ID'
$concertId = "fifo-sim-$runId"
$tag = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($concertId)).TrimEnd('=').Replace('+','-').Replace('/','_')
$base = 'demo-ticketing:queue-sim:' + $runId + ':{' + $tag + '}:'
$keys = @('waiting','deadlines','admitted','expired','sequence') | ForEach-Object { $base + $_ }
docker exec demo-ticketing-redis redis-cli DEL @keys
```
