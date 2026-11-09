# Issue #18 — Redis 대기열 A/B 성능 실험

이 구현은 **최대 100 VU** 단계 전용이다. 200/500 VU, 도착률 실험, leave 없는 TTL 실험은 실행하지 않는다. 운영 서비스 코드와 DB 스키마를 수정하지 않는다.

현재 실행은 1 VU 환경 시작 중 가용 RAM 부족으로 보호 중단되었다. A/B API·DB 동작 및 10/50/100 VU 성능 비교는 아직 완료하지 않았다. [실제 실행 기록](queue-performance/실행기록-2026-11-07.md)을 먼저 확인한다.

## A/B 구성과 운영 JAR 분리

- A `no-queue`: 별도 source set의 QueueService 구현에서 `requireAdmitted()`만 생략한다.
- B `redis-queue`: 같은 별도 구현이 기존 `super.requireAdmitted()`를 호출한다.
- 같은 Controller·인증·좌석 선점·예매 확정·트랜잭션·조건부 UPDATE·Outbox 저장을 사용한다.
- source set은 `src/queuePerf/java`이며 운영 `main`에 포함하지 않는다. 일반 `bootJar`에 성능 클래스가 없는지 ZIP 항목과 SHA-256으로 검사한다.
- 정상 운영 JAR에는 테스트 설정을 읽는 우회 코드가 없다. 배포용 우회 환경변수·헤더·API를 추가하지 않았다.
- 전용 실행기는 실행 ID, `perf_<실행ID>` DB 사용자, loopback 주소, DB 포트, 실제 Redis run_id를 확인한다.

빌드는 `prepareQueuePerf`로 수행한다. 클래스패스와 검사할 정확한 운영 JAR 경로를 `build/queue-perf/`에 기록한다.

## 격리 환경과 측정 조건

매 실행마다 새 MySQL·Redis 컨테이너와 익명 볼륨을 사용하고 localhost 임의 포트를 연결한다. 기존 Compose 컨테이너·데이터·`.env`는 변경하지 않는다. 이름과 컨테이너 ID가 이번 실행과 일치할 때만 종료한다.

| 항목 | 설정 |
|---|---|
| JVM | Java 17, Xms 128MiB, Xmx 512MiB, metaspace 상한 192MiB, direct memory 상한 128MiB |
| MySQL | mysql:8.0, CPU 2개 상한, 메모리 1GiB, buffer pool 128MiB, max_connections 40 |
| Redis | redis:7.4-alpine, CPU 1개 상한, 메모리 256MiB, AOF 활성 |
| Hikari | 최대/최소 10개, 계측 연결은 풀 밖에 1개 별도 유지 |
| Tomcat | 최대 작업 스레드 200, virtual threads 비활성 |
| 정책 | ALLOW/FIFO, CAPTCHA disabled, 기본 입장 정원 100·입장 TTL 5분 |
| 배경 작업 | 양쪽 모두 만료 스케줄러·외부 BTS 전송 비활성. Outbox DB 저장은 유지 |
| 사용자·좌석 | 같은 수의 사용자, 여정별 고유 좌석. 409를 만들기 위한 인기 좌석 경쟁은 없음 |
| 워밍업 | 각 VU가 정확히 5건 예매. 최소 15초 구간. 별도 공연·k6 프로세스·HTML·JSON |
| 본 측정 | 기본 60초 constant-vus, grace 20초, think time 1초 |
| 반복 | 10/50/100 VU 각 3회, A→B / B→A / A→B 순서 |

주 측정은 클라이언트가 예매 완료 후 기존 leave API를 명시적으로 호출한다. **서버가 자동 반환하는 것이 아니다.** 실패 시에도 입장 자리를 정리한다. 입장 대기 폴링은 0.5초다. 100 VU까지는 기본 정원도 100이므로 주로 대기열의 추가 비용을 관측할 수 있으며 과부하 완화를 입증한다고 가정하지 않는다.

## 메모리·페이징 보호

- 실행 전 가용 물리 RAM이 **2GiB 미만**이면 빌드 및 컨테이너 생성 전에 중단한다. 이는 이번 노트북용 시작 조건이며 서버의 보편적인 최소 사양을 뜻하지 않는다.
- 실행 중 가용 RAM 768MiB 미만 3회 연속, 또는 가용 1GiB 미만과 Pages Input/sec 1000 초과 5회 연속, 또는 호스트 전체 대비 k6 CPU 80% 초과 5회 연속이면 중단한다.
- 타인의 애플리케이션이나 기존 DB를 자동 종료하지 않는다. 중단 후 메모리를 확보하거나 별도 부하 발생기를 사용한다.
- Pages Input/sec에는 파일 페이지 읽기도 포함되므로 이를 전부 스왑으로 해석하지 않는다.
- 수집 실패나 자원 보호 중단을 성능 성공으로 보고하지 않는다. 자원이 부족한 채 임계값을 낮춰 강행하지 않는다.

## 실행 방법

PowerShell, Docker Desktop, Java 17, Python 3, k6가 필요하다. Python 실행기는 표준 라이브러리만 사용한다.

```powershell
# 메모리 확보 후 새 결과 디렉터리에서 예비 실험 4개: 1/10 VU × A/B
./scripts/Test-QueuePerformance.ps1 -Stage pilot

# 위 실행에서 출력한 같은 결과 경로를 사용한다.
./scripts/Test-QueuePerformance.ps1 -Stage comparison `
  -Results ./build/queue-performance-YYYYMMDD-HHMMSS -Seconds 60 -Repeats 3

# 서버나 부하 발생기를 실행하지 않고 저장된 결과만 집계
./scripts/Test-QueuePerformance.ps1 -Stage report `
  -Results ./build/queue-performance-YYYYMMDD-HHMMSS
```

comparison은 동일 결과 디렉터리에 1/10 VU A/B 예비 실험 4개의 성공 기록이 있어야 시작한다. 기존 결과는 덮어쓰지 않는다. 실패한 실험을 재시도할 때는 새 디렉터리를 사용한다. 워밍업과 본 측정은 다른 데이터·결과 파일을 사용하며, 워밍업 결과를 본 측정 지표에 합치지 않는다.

## 대시보드·결과 파일

각 k6 실행에 다음을 설정한다.

```text
K6_WEB_DASHBOARD=true
K6_WEB_DASHBOARD_HOST=127.0.0.1
K6_WEB_DASHBOARD_PORT=<실행별 임의 포트>
K6_WEB_DASHBOARD_PERIOD=1s
K6_WEB_DASHBOARD_OPEN=false
K6_WEB_DASHBOARD_EXPORT=<실행별 HTML 경로>
```

실행 중 출력되는 localhost 주소를 브라우저에 붙여 넣으면 실시간 대시보드를 볼 수 있다. 종료 후에는 export된 HTML을 직접 열 수 있다. 1초 집계 주기를 사용하며 예비 본 측정도 10초로 설정하여 짧은 실행에서 그래프가 빠지는 문제를 피한다. 다만 **k6 시작 전 중단에는 k6 HTML·요약 JSON이 생성되지 않는다.** 이를 빈 성공 보고서로 대체하지 않는다.

```text
build/queue-performance-<시각>/
  production-jar-check.json
  host-preflight.json
  pilot/no-queue/1vu-run1-environment.json
  pilot/no-queue/1vu-run1.html
  pilot/no-queue/1vu-run1-summary.json
  comparison/no-queue/10vu-run1.html
  comparison/no-queue/10vu-run1-summary.json
  comparison/redis-queue/10vu-run1.html
  comparison/redis-queue/10vu-run1-summary.json
  ...
  results.json
  comparison.csv
  resource-summary.json
  index.html
```

각 실행에는 `*-resources.jsonl`, `*-details/server-metrics.jsonl`, `*-db.json`, `*-redis-final.json`, 서버·k6 로그도 저장한다. 위 파일들은 해당 단계까지 실행했을 때만 존재한다. 세션 토큰을 담는 임시 입력 파일은 finally에서 삭제하고 결과 요약·로그에는 포함하지 않는다. 전체 `build/`는 Git 제외 대상이다.

```powershell
# 집계 HTML 또는 개별 k6 HTML을 파일로 직접 열기
Invoke-Item ./build/queue-performance-<시각>/index.html
Invoke-Item ./build/queue-performance-<시각>/comparison/redis-queue/10vu-run1.html
```

HTML 파일을 더블클릭해 열어도 된다. 현재 보존한 `build/queue-performance-issue18-20261107/index.html`은 **중단 진단 보고서**이며 k6 성능 대시보드가 아니다.

## 지표 정의와 감사

- `api_latency`: API 전체 및 hold/confirm/join/leave별 평균·p95. 전체 API 평균은 호출 구성이 다른 A/B의 업무 처리 성능을 직접 비교하는 값이 아니다.
- `booking_processing`: 선점 요청 시작부터 확정 응답까지.
- `queue_wait`: join 시작부터 ADMITTED 응답 관측까지. A의 0은 대기열이 없다는 정의이며 Redis 검사를 측정한 값이 아니다.
- `booking_journey`: 사용자 여정 시작부터 확정 응답까지. leave와 think time은 완료 시간에서 제외하되 다음 여정 시작에는 영향을 준다.
- HTTP RPS와 예매 API RPS는 설정된 본 측정 구간에서 시작한 요청 수/구간 초. 예매 TPS는 같은 구간 내 신규 201 응답 수/구간 초다.
- `booking_created`는 grace 완료까지 포함한 총 신규 예매로 DB 건수와 비교한다. 응답 시간은 본 측정에서 시작해 grace에 완료된 요청도 포함한다.
- HTTP 5xx, 타임아웃, 기타 네트워크 오류, 정상 4xx 거절을 별도 카운터로 저장한다. 거절이 많아 응답이 빨라지는 것을 성공으로 해석하지 않는다.
- Hikari active/total/waiters, MySQL 연결·실행 스레드·누적 락 대기·Performance Schema 락 대기를 1초 간격으로 수집한다. 모니터 연결 1개가 MySQL 연결 수에 포함된다.
- Java/k6 CPU는 논리 CPU 전체=100%, Docker CPU는 논리 CPU 1개=100%다. RSS·private committed·JVM heap·컨테이너 메모리는 서로 다른 값으로 저장한다.
- 대기열 길이는 ZCARD 표본이므로 정확한 최대가 아닌 관측 최대다. 계측 SQL·Redis 명령의 비용도 양쪽에 동일하게 존재한다.
- 완료 후 실제 DB의 신규 예매·좌석·티켓·Outbox 수를 응답 수와 대조하고 기존 정합성 SQL을 실행한다. Redis waiting/admitted 자리가 모두 반환됐는지도 검사한다.

같은 PC의 Windows·Docker VM·Java·k6 자원 경합은 결과의 한계다. 미수집 지표는 null/미수집으로 보존하며, 아직 실행하지 않은 조건에 예상 효과를 측정값처럼 채우지 않는다.
