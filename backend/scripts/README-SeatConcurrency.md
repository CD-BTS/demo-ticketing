# 좌석 단위 동시성 제어 — Issue #23

공연 행의 `FOR UPDATE`를 제거했다. 서로 다른 사용자의 서로 다른 좌석 요청은 같은 공연에서도 병렬로 처리한다. API 경로와 응답 구조, Redis 입장 조건, 선점 시간, 취소·재판매 규칙은 유지한다.

## 기존 테이블 활용 검토

`seat_holds`의 ACTIVE 행만 잠그면 아직 선점이 없는 사용자의 동시 생성은 보호하지 못한다. `bookings.hold_id` UNIQUE는 같은 선점의 중복 확정만 막는다. 사용자 행 전체를 잠그면 다른 공연까지 직렬화하고 외래 키 참조와도 경합한다. 따라서 `(user_id, concert_id)` PK를 가진 `reservation_user_concert_guards`를 추가했다. 이 행은 선점 생성·변경에서만 사용하며 좌석 소유권은 저장하지 않는다. 사용자·공연 삭제 시 CASCADE로 정리된다. 평상시에는 사용자·공연 조합마다 한 행을 유지한다.

## 잠금 순서와 트랜잭션

1. 선점 생성·변경은 사용자×공연 행을 생성하거나 조회하여 잠근다.
2. 요청 좌석, 기존 선점 좌석, 회수할 만료 선점의 전체 좌석을 수집한다.
3. `concert_seats.id`를 정렬해 PK 단건 `FOR UPDATE`를 순서대로 실행한다.
4. 관련 기존 `seat_holds.id`를 오름차순으로 잠근다. 취소는 좌석을 잠근 뒤 해당 `bookings`를 잠근다.
5. 잠금 이후의 상태·현재 시각·입장 권한을 다시 검사한다.
6. 현재 상태·점유 ID를 조건으로 UPDATE하고 변경 건수를 검사한다. 선점·예매·티켓·Outbox는 같은 트랜잭션으로 커밋한다.

선점 명세와 예매 좌석 명세는 생성 후 변경하지 않으므로 잠금 대상 발견에 활용한다. 만료로 인해 추가 좌석을 잠가야 한다면 이미 잡은 잠금 뒤에 추가하지 않고 전체 롤백 후 수집부터 다시 시작한다. 일반 조회의 관찰 결과만으로 상태 전이를 승인하지 않는다.

`ReservationTransactions`는 `READ_COMMITTED`, `REQUIRES_NEW`로 각 변경 작업을 실행한다. 잠금 집합 변경 및 Spring의 일시적 DB 예외에 한해 최대 5회 시도한다. 이전 시도를 롤백한 뒤 새 트랜잭션으로 재실행하며 한도 초과는 기존 503 `SERVICE_UNAVAILABLE`이다. 기존 상위 트랜잭션에 참여하는 구조가 아니므로 향후 호출자가 다른 변경을 함께 원자적으로 묶으려면 이 경계를 명시적으로 재검토해야 한다.

중복 확정은 좌석·선점을 잠근 뒤 기존 예매를 다시 조회하여 최초 201, 재요청 200 및 같은 예매 ID를 반환한다. 일반 좌석 충돌은 기존 409다. 예상하지 못한 변경 건수 불일치는 롤백하고 서버 오류로 남긴다.

`expire(concertId)`는 후보를 일반 조회한 뒤 선점 하나마다 별도 트랜잭션으로 처리한다. 신규 선점 요청에서 공연 전체의 만료 선점을 일괄 처리하지 않는다. 요청 관련 만료 선점 및 사용자의 기존 만료 선점만 처리하고 나머지는 기존 주기 작업이 담당한다.

외래 키 검사에 필요한 공연 행의 공유 잠금은 남을 수 있으나 공연 행의 배타 잠금은 사용하지 않는다. 다른 사용자·서로 다른 좌석 요청 간 공유 잠금은 양립한다. Redis 분산 잠금과 새 라이브러리는 추가하지 않았다.

## 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/bts/demoticketing/booking/service/ReservationService.java` | 공연 잠금 제거, 좌석 집합 수집·재검증, 모든 변경 경로의 잠금 순서 통일 |
| `src/main/java/com/bts/demoticketing/booking/repository/ReservationRepository.java` | 사용자×공연·좌석·선점·예매 잠금과 조건부 상태 전이 검증 |
| `src/main/java/com/bts/demoticketing/booking/service/ReservationTransactions.java` | 신규 트랜잭션 단위 재시도와 503 처리 |
| `db/schema.sql`, `db/03-seat-concurrency.sql` | 신규 DB 및 기존 DB용 전용 테이블 DDL |
| `compose.yaml`, `scripts/Initialize-Database.ps1` | 초기화 SQL 적용 경로 반영 |
| `src/test/java/com/bts/demoticketing/booking/SeatConcurrencyMySqlTests.java` | 좌석 경쟁·병렬성·상태 전이·전체 롤백 테스트 |
| `src/test/java/com/bts/demoticketing/concert/ConcertApiMySqlTests.java` | 추가 테이블을 반영한 스키마 검사 |
| `scripts/Test-SeatConcurrency.ps1` | 전용 서버·데이터 준비, k6 실행, DB 검사·정리 |
| `scripts/seat-concurrency/seat-concurrency.js`, `scripts/seat-concurrency/verify.sql` | 15개 k6 시나리오 및 실제 DB 정합성 검사 |
| `scripts/README-SeatConcurrency.md`, `scripts/seat-concurrency/검증결과-2026-11-07.md` | 설계·SQL 적용·실행 방법과 실제 결과 |

## 기존 DB에 적용할 SQL

서버의 예약 쓰기를 멈추고 추가 DDL을 적용한 뒤 모든 서버를 같은 버전으로 시작한다. 이전 공연 잠금 버전과 새 잠금 규약을 혼용하지 않는다.

```powershell
. ./scripts/MySql-Tools.ps1
Invoke-DemoMySql -Sql (Get-Content -Raw -Encoding UTF8 ./db/03-seat-concurrency.sql)
```

기존 테이블 변경·삭제·데이터 초기화는 없다. 생성하는 테이블은 다음 하나다.

```sql
CREATE TABLE IF NOT EXISTS reservation_user_concert_guards (
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, concert_id),
  CONSTRAINT fk_reservation_guard_user FOREIGN KEY (user_id)
    REFERENCES demo_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_reservation_guard_concert FOREIGN KEY (concert_id)
    REFERENCES concerts(id) ON DELETE CASCADE
) ENGINE=InnoDB;
```

새 볼륨은 `compose.yaml`의 초기화 SQL로 생성한다. `db/schema.sql` 및 `Initialize-Database.ps1 -Apply`에도 반영되어 있다. 기존 볼륨은 Compose 재시작만으로 초기화 SQL이 다시 실행되지 않으므로 위 별도 적용 명령을 사용한다. Flyway는 사용하지 않는다.

## MySQL·Redis 통합 테스트

기존 Demo MySQL(`localhost:3308`)과 Redis(`localhost:6380`)를 사용한다.

```powershell
. ./scripts/MySql-Tools.ps1
Read-DemoEnvironment -Path ./.env
$env:DB_PORT='3308'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6380'
$env:REDIS_DATABASE='0'
$env:REDIS_USERNAME=''
$env:REDIS_PASSWORD=''
.\gradlew.bat test redisTest mysqlTest --no-daemon
```

`SeatConcurrencyMySqlTests`는 2·10·100계정 동일 좌석/다른 좌석 선점, 겹치는 다중 좌석·역순 선택, 동일 사용자 동시 생성·변경, 변경과 확정, 중복 확정, 해제·취소·만료와 경쟁, 잠금 대상 재수집, 재시도 전 전체 롤백 및 재시도 소진을 검사한다. 모든 시나리오의 종료 시 좌석·ACTIVE 선점·유효 예매·티켓·Outbox의 참조 정합성을 SQL로 검사한다. 기존 예약 테스트의 저장 실패 롤백, API·Redis 호환성 검증도 유지한다.

병렬성 테스트는 첫 트랜잭션이 좌석 S1을 잠근 직후 Latch로 멈추고, 다른 사용자의 S2 요청이 첫 커밋 전에 완료되는지 검사한다. 동일 S1 경쟁 요청은 대기 후 409가 되어야 한다. 콘솔/XML에 좌석 잠금 호출 시간과 독립 좌석 완료 시간을 출력한다. 단순한 전체 성공 개수나 짧은 고정 지연 임계값만으로 병렬성을 판정하지 않는다. DB의 실제 `data_lock_waits`는 조회하지 않으며 이 시간은 애플리케이션에서 측정한 호출 경과 시간이다.

## k6 실행 및 DB 검사

```powershell
./scripts/Test-SeatConcurrency.ps1
# 일부만 실행
./scripts/Test-SeatConcurrency.ps1 -Modes same,different,confirm -UserCounts 2,10
```

전용 포트 18086의 서버를 시작하고 대기열 정원 150, 정책/CAPTCHA disabled, BTS 전송·주기 작업 비활성으로 실행한다. 준비 과정에서 실제 로그인 API와 Redis 대기열을 통과시킨다. 경쟁 구간은 계정당 요청 1회이며, `per-vu-iterations`와 공통 시작 시각을 사용한다. VU 100개가 DB 연결 100개를 뜻하지는 않는다. 기본 Hikari 풀 상한은 10이며 독립 좌석 요청도 연결 풀에서 대기할 수 있다.

| 모드 | 동작 | 기대 결과 |
|---|---|---|
| same | 서로 다른 계정, 동일 좌석 | 201 1건, 나머지 409, ACTIVE 선점 1건 |
| different | 서로 다른 계정, 다른 좌석 | 전부 201, 계정 수만큼 ACTIVE 선점 |
| overlap | 서로 다른 계정, S1·S2 또는 S2·S3 | 201 1건, 나머지 409, 점유 2석 |
| same-user | 같은 계정의 서로 다른 선택 | 전부 201, 최종 ACTIVE 선점·점유 각 1건 |
| confirm | 같은 유효 선점의 중복 확정 | 201 1건, 나머지 200, 예매·티켓·Outbox 각 1건 |

각 모드를 2·10·100 VU로 실행한다. p95는 준비 요청을 제외한 `race_duration`이며 전체 경과 시간에는 준비·공통 시작 대기가 포함된다. 응답 임계값 성공 후에도 `seat-concurrency/verify.sql`로 실제 DB 결과와 중복·불일치 0건을 검사해야 최종 PASS다. k6 실패 시에도 DB 결과를 수집한다. 테스트 계정 ID는 기존 API의 30자 제한을 지킨다.

결과는 `build/seat-concurrency-<실행ID>/`에 `results.json`, 시나리오별 `*-summary.json`, `*-db.json`, 서버 로그로 보존한다. 세션 토큰은 저장하지 않는다. 기본적으로 생성한 정확한 공연·사용자와 해당 Redis 키만 정리하고 서버를 종료한다. `-KeepFixtures`는 DB 데이터를 보존하여 수동 검사할 때 사용한다. 기존 시드·다른 테스트 실행 데이터는 삭제하지 않는다.

## 검증의 범위

동일 좌석의 유효한 확정 예매는 최대 1건이다. 취소 이력과 재판매 예매는 별도 행으로 남는다. 실제 PG 결제·BTS 서버 연동, 다중 애플리케이션 인스턴스 및 DB 장애 전환 테스트는 이번 로컬 검증 범위에 포함하지 않는다. 공통 MySQL 잠금이므로 JVM별 메모리 락에 의존하지 않지만 다중 인스턴스 실측을 했다는 의미는 아니다.
