# Issue #25 — 취소·선점 만료 시 좌석 복구 검증

기존 백엔드의 취소·만료 복구 로직과 Issue #23의 좌석 단위 동시성 제어를 검증한다. 프론트엔드, API 계약, Redis 대기열 및 DB 스키마는 변경하지 않는다.

실제 실행 결과는 [2026-11-07 검증 결과](seat-recovery/검증결과-2026-11-07.md)에 정리했다.

## 실행 방법

Docker Desktop, Java 17, Gradle Wrapper를 사용한다. 기존 Demo MySQL·Redis를 시작하거나 `.env`를 읽을 필요는 없다.

```powershell
# 기존 예약 17개 + Issue #23 동시성 23개 + 공연 API 3개를 먼저 확인
./scripts/Test-SeatRecovery.ps1 -BaselineOnly

# 일반·Redis·MySQL 및 새 복구 테스트 전체 실행
./scripts/Test-SeatRecovery.ps1
```

실행기는 매번 전용 MySQL·Redis 컨테이너를 생성하고 `127.0.0.1`의 임의 포트를 사용한다. MySQL 사용자는 실행마다 생성하는 `recovery_<실행ID>`이며 비밀번호도 매번 생성한다. 기존 볼륨과 공용 DB·Redis는 사용하지 않는다. 현재 SQL 4개를 읽기 전용으로 연결하여 기존 스키마를 초기화한다. 추가 DDL은 없다.

테스트 종료 시 SQL 스냅샷과 JUnit XML을 `build/seat-recovery-<실행ID>/`에 저장한 뒤, 생성 당시의 이름·컨테이너 ID를 대조하여 해당 컨테이너와 익명 볼륨을 자동 제거한다. 기존 Demo 컨테이너는 중지하지 않는다. 세션 토큰·DB 비밀번호는 결과 파일에 저장하지 않는다.

`--rerun-tasks`로 실행하므로 다른 DB에서 실행한 과거 성공 결과를 재사용하지 않는다. 실행기는 성공·실패 결과를 모두 보존하고 테스트 실패 시 오류를 반환한다.

## 테스트 구성

| 파일 | 역할 |
|---|---|
| `SeatRecoverySupport.java` | 격리 환경 검사, 실제 HTTP 클라이언트·Redis 입장 준비, 주입 Clock, 7개 테이블의 SQL 정합성 검사 |
| `SeatRecoveryMySqlTests.java` | 취소·재예매, 중복·타인 취소, 5분 경계, 만료 후 재선점, 취소 저장 실패의 전체 롤백, 경쟁 요청 |
| `SeatRecoverySchedulerMySqlTests.java` | 실제 기본 5초 스케줄러에 의한 자동 물리적 해제와 확정 예매 보호 |
| `Test-SeatRecovery.ps1` | 격리 컨테이너 생성, 기존·신규 테스트 실행, 결과 수집, 격리 환경 정리 |
| `seat-recovery/verify.sql` | 전체 실행 후 7개 테이블의 상태·참조·이력·Outbox를 다시 검사하는 읽기 전용 SQL |

새 테스트는 실행기의 `SEAT_RECOVERY_ISOLATED` 표시가 없으면 비활성화된다. 컨텍스트 생성 시 전용 DB 사용자와 포트도 검사한다. 따라서 일반 `mysqlTest` 명령으로 공용 DB에 자동 만료 스케줄러를 켜지 않는다. 새 테스트의 실행 성공 여부는 격리 실행기의 결과를 기준으로 판단한다.

## HTTP 취소 및 재예매

MockMvc 대신 임의 포트의 실제 Spring Boot 서버에 Java HttpClient로 요청한다. 사용자 등록·Bearer 인증·Redis 대기열 입장도 실제 API를 통과한다.

1. A가 좌석을 선점하고 예매 확정한다. HELD→BOOKED, 선점 CONVERTED, ISSUED 티켓과 BOOKING Outbox를 SQL로 확인한다.
2. 취소 API를 호출하여 예매·티켓 CANCELLED, 취소 시각, 좌석 AVAILABLE 및 두 점유 ID NULL, CANCEL Outbox 1건을 확인한다.
3. 예매 내역·좌석 조회 API가 최신 서버 상태를 반환하는지 확인한다.
4. B가 같은 좌석을 재선점·확정한다. 이전 취소의 재요청이 새 예매를 해제하지 않는지 확인한다.
5. 다른 사용자의 취소는 403, 동일 예매의 동시 취소는 모두 200이며 CANCEL 이벤트는 1건인지 확인한다.
6. CANCEL Outbox 저장 실패를 주입하면 예매·티켓·좌석·취소 시각·version이 모두 롤백되는지 확인한다. 이때 기대하는 500 로그는 운영 결함을 의미하지 않는다.

예매·선점·좌석 명세는 삭제하지 않는다. 취소 후 재판매 시 이전 CANCELLED 예매와 새 CONFIRMED 예매가 함께 남는 기존 정책을 유지한다.

## 논리적 만료와 물리적 해제 구분

모든 새 테스트는 선점 기간을 `PT5M`으로 명시하고, DB에 저장된 `expires_at-created_at`이 정확히 5분이며 HTTP 응답 시각과 일치하는지 검사한다. Clock은 UTC이며 마이크로초 정밀도로 제어한다.

스케줄러를 끈 HTTP 테스트에서는 만료 시각의 **1마이크로초 전·정확한 경계·1마이크로초 후**를 검사한다.

- 직전: 확정 가능.
- 경계·직후: 410 HOLD_EXPIRED. 좌석 조회 응답은 AVAILABLE이지만, 아직 DB는 HELD/ACTIVE일 수 있음을 확인한다.
- 이후 B가 선점하면 관련 만료 선점 전체가 해제되고 B의 새 선점이 만들어지는지 확인한다.
- 만료 자체로 예매·티켓·CANCEL Outbox가 생기지 않는지 확인한다.

스케줄러 테스트는 `demo.jobs.enabled=true`, `demo.expiry-poll-ms=5000`으로 실행한다. 스케줄러 메서드는 Spy로 실행 횟수만 관찰하며 실제 구현을 대체하거나 테스트에서 직접 호출하지 않는다.

1. A의 다중 좌석 선점과 2분 뒤 생성한 별도 유효 선점을 준비한다.
2. 5분 직전까지 Clock을 이동하고 실제 스케줄러가 실행되어도 A의 선점이 유지됨을 확인한다.
3. 정확한 만료 시각으로 Clock을 이동한다.
4. **이후 HTTP 요청 및 직접 `expire()` 호출 없이 JDBC SELECT만 폴링**하여 A의 선점 EXPIRED와 DB 좌석 AVAILABLE·점유 ID 제거를 확인한다. 별도 유효 선점은 HELD로 유지되어야 한다.
5. 물리적 복구를 확인한 다음에만 만료 선점 확정 거부·B의 재선점/재예매 API를 호출한다.

별도 시나리오에서는 스케줄러가 ACTIVE 선점만 만료시키고 CONVERTED 선점의 BOOKED 좌석·CONFIRMED 예매·ISSUED 티켓은 유지하는지 확인한다. 각 스케줄러 테스트 클래스 종료 시 컨텍스트를 닫아 다른 테스트에 주입 Clock과 작업이 영향을 주지 않게 한다.

## DB 정합성과 경쟁 검증

각 새 테스트의 종료 시 다음을 SQL로 검사한다.

- AVAILABLE 좌석에는 이전 선점·예매의 점유 ID가 없다.
- HELD 좌석의 현재 선점은 ACTIVE이고 선점 명세와 일치한다.
- BOOKED 좌석은 유효 CONFIRMED 예매·명세·티켓과 일치한다.
- 종료된 선점이 현재 점유자로 남거나 동일 사용자·공연에 ACTIVE 선점이 중복되지 않는다.
- 동일 좌석의 유효 CONFIRMED 예매는 최대 1건이다.
- 선점·예매 명세와 티켓 이력, BOOKING/CANCEL Outbox가 유지되고 중복되지 않는다.
- 테스트 사용자의 Outbox가 존재하지 않는 예매를 참조하지 않는다.

취소와 B의 선점, 만료된 A의 확정과 B의 재선점을 실제 HTTP로 동시에 요청한다. 각각 3회 반복하며 응답과 DB를 모두 확인한다. Issue #23의 2·10·100명 경쟁·별도 좌석 병렬 처리·선점 변경·취소·만료 경쟁 23개도 함께 회귀 검증한다.

## 범위와 미검증 항목

시간 주입으로 5분 경계를 검증하므로 벽시계로 실제 5분을 기다리는 테스트는 별개다. 실제 스케줄러의 5초 주기는 단축하지 않는다. DB 복구 관측 지연에는 작업 실행 주기와 SQL 폴링 지연이 포함된다.

이번 작업에는 브라우저 UI/E2E, 프론트엔드 수정, 실제 PG·BTS 서버 연동, 다중 서버·DB 장애 전환, k6 재실행을 포함하지 않는다. HTTP API 검증 성공을 브라우저 화면 검증 성공으로 보고하지 않는다.
