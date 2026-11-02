# 데모 티켓팅 백엔드 — 예매 구현

Java 17 / Spring Boot 4.1.1 / Gradle Wrapper 9.7.1 / MySQL 8.0.16+.

공연 조회와 데모 세션, 좌석 선점·확정·취소·본인 내역을 구현했다. Controller→Service→Repository와 도메인 Entity/DTO 분리를 유지한다. 공연 조회는 기존 JPA, 잠금이 필요한 예약 도메인은 명시적 JDBC Repository 및 Spring 트랜잭션을 사용한다.

| Method | URL | data |
|---|---|---|
| GET | /api/v1/concerts | Concert[] |
| GET | /api/v1/concerts/{concertId} | Concert |
| GET | /api/v1/concerts/{concertId}/seats | Seat[] (선택 Bearer로 본인 선점 표시) |
| POST | /api/v1/users | {userId,token,expiresAt}, 데모 세션 시작 |
| POST | /api/v1/concerts/{concertId}/seat-holds | SeatHold, Bearer 필수 |
| DELETE | /api/v1/seat-holds/{holdId} | null, Bearer 필수 |
| POST | /api/v1/bookings | Booking, Bearer 필수 |
| POST | /api/v1/bookings/{bookingId}/cancel | Booking, Bearer 필수 |
| GET | /api/v1/bookings/me | 본인 Booking[], Bearer 필수 |

공개 조회에는 인증/대기열을 요구하지 않는다. 쓰기는 24시간 데모 Bearer 세션으로 소유권을 확인한다. ID 입력만으로 같은 신원을 재시작하는 공유 데모 방식이며 실제 계정 소유권 증명이 아니다. 실제 PG/환불·대기열·QR/검표·Feature 추출은 미구현이다. BTS BOOKING/CANCEL 전송은 구현했지만 실제 발급 설정이 없는 기본 환경에서는 PENDING으로 보존한다.

## 참고한 기존 BTS 코드

인접한 `../backend`의 실제 소스를 읽기 전용으로 확인했다.

- build.gradle, Gradle Wrapper: 동일한 Java 17 / Boot 4.1.1 / Gradle 9.7.1 기준 사용
- user/controller/UserController, user/entity, user/dto: 레이어·DTO 분리 방식 참고
- common/response/ApiResponse, ResponseCode, common/exception/ApiException, GlobalExceptionHandler: 같은 구조로 데모 패키지에 적용. 공연 도메인의 CONCERT_NOT_FOUND 추가
- db/schema.sql: 별도 SQL 수동 관리 방식을 유지

실제 패키지는 `com.bts.demoticketing`이다. BTS의 사용자/인증/이벤트 Entity·Service·Repository·DB는 가져오거나 수정하지 않았다. common의 HTTP 상태/code 분리와 validation data.errors, MVC 헤더 보존을 유지한다. Flyway는 없으며 `ddl-auto=none`, `spring.sql.init.mode=never`로 앱 시작 시 DDL/seed를 실행하지 않는다.

## 빌드

저장소 루트에서:

```powershell
. ./backend/scripts/Invoke-Backend.ps1 -Task Build
```

또는 backend 폴더에서 `./gradlew.bat build`(Unix는 `bash ./gradlew build`). JDK 17과 최초 의존성 다운로드가 필요하다. 일반 build에는 DB 연결이 필요 없는 MVC 테스트만 포함된다. 기존 Gradle 캐시와 배포본은 실행 환경의 GRADLE_USER_HOME을 사용한다.

## DB 구성 — 데모 전용 Compose

기존 BTS `fairticket-mysql`(3307, backend_mysql_data)은 유지한다. 데모는 **demo-ticketing-mysql / MySQL 8.0 / 127.0.0.1:3308 / demo_ticketing_db / demo_user**를 사용하며 별도 `demo-ticketing-mysql-data` 볼륨에 저장한다. Compose 프로젝트 이름도 demo-ticketing으로 분리했다. BTS DB·볼륨·사용자 비밀번호에 접근하거나 변경하는 초기화 경로가 없다.

저장소 루트 기준:

```powershell
# 처음 준비할 때만 복사하고 DB_PASSWORD, MYSQL_ROOT_PASSWORD를 서로 다른 난수로 입력한다.
# 기존 .env는 덮어쓰지 않는다.
Copy-Item backend/.env.example backend/.env
docker compose --env-file backend/.env -f backend/compose.yaml config --quiet
docker compose --env-file backend/.env -f backend/compose.yaml up -d --wait --wait-timeout 180
```

현재 로컬에는 독립적인 임의 비밀번호가 입력된 gitignored backend/.env를 준비했다. 원문은 출력하지 않는다. Compose는 필수 비밀번호가 비어 있으면 시작을 거부한다. MYSQL_ROOT_PASSWORD는 **새 데모 컨테이너 초기 생성용**이고 Spring 앱은 DB_PASSWORD/demo_user만 사용한다. 이전 BTS root 재시도용 DEMO_MYSQL_ROOT_PASSWORD·관리자 override·사용자/GRANT 수동 생성은 스크립트에서 제거했다.

.env와 동일한 키의 shell 환경변수는 Compose 치환/앱 실행에서 우선할 수 있으므로 다른 프로젝트의 DB_PASSWORD/DB_PORT 등을 export한 shell은 정리하고 실행한다. `docker compose config`는 비밀 값을 표시할 수 있으므로 설정 검사에는 `config --quiet`를 사용한다.

| 파일 | 목적 |
|---|---|
| compose.yaml | 독립 컨테이너·포트·볼륨·계정·healthcheck |
| db/schema.sql | 기존 핵심 8개 테이블 생성 |
| db/02-booking-support.sql | 세션/Outbox 2개 추가. 기존 볼륨에도 -Apply로 적용 |
| db/seed.sql | 초기 볼륨에서 공연 3건/공연별 96석 삽입, 재실행도 누락 행만 추가 |
| db/verify.sql | 정확한 합계·공연별 수량·가격·좌석 코드·제약 조회 |
| db/00-create-database.sql | Compose 밖에서 수동 구성할 때의 선택 SQL. Compose에서는 MYSQL_DATABASE가 DB 생성 |

schema.sql/seed.sql/02-booking-support.sql은 readonly로 /docker-entrypoint-initdb.d/10-schema.sql, 20-seed.sql, 30-booking-support.sql에 연결한다. **비어 있는 새 데이터 디렉터리에서만** 자동 실행한다. 기존 볼륨에서는 재시작이나 .env 변경만으로 DB·비밀번호·schema가 다시 초기화되지 않는다. 이번에는 새 볼륨으로 시작했으며 기존 BTS 볼륨을 재사용하지 않았다.

다음 명령은 고정된 demo-ticketing-mysql에서 demo_user로만 동작한다.

```powershell
. ./backend/scripts/Initialize-Database.ps1        # 조회·검증만
. ./backend/scripts/Initialize-Database.ps1 -Apply # 기존 행 보존하며 schema/seed 재적용 후 검증
```

기존 핵심 8개 테이블에 demo_sessions/outbox_events를 추가해 총 10개다. bookings.hold_id UNIQUE·점유 FK/CHECK·공연 행 잠금을 재사용한다. 확정 시 좌석별 admission_tickets도 생성한다. QR 자격증명/대기열 테이블은 아직 없다. 신규 DemoUser/ReservationRecords는 JDBC가 매핑하는 도메인 record이며 HTTP 응답 DTO와 별개다.

MySQL 공식 이미지가 demo_user에 demo_ticketing_db 범위의 ALL 권한을 부여하며 기존 BTS DB 권한은 없다. 계정은 데모 로컬 개발/SQL 재적용 용도다. 향후 운영 시 DDL 담당과 런타임 계정을 분리하는 정책을 별도 적용한다. root를 앱 접속 계정으로 사용하지 않는다.

PK/FK/UNIQUE/INDEX/CHECK와 1~4석 일괄 선점·소유권·만료·경쟁 제어를 적용했다. 모든 좌석 쓰기는 먼저 해당 공연 행을 SELECT FOR UPDATE로 잠그고 READ COMMITTED에서 현재 상태를 재조회한다. 공연 단위 직렬화는 단순하고 다중 서버에도 안전하지만 같은 공연의 처리량을 제한하므로 향후 좌석별 잠금 세분화 여지가 있다.

IF NOT EXISTS는 migration이 아니므로 기존 정의가 다르면 별도 변경 SQL을 검토한다. seed는 기존 행/판매 상태를 갱신·삭제하지 않는다. 순차 재실행은 검증했으며 동시 seed 실행은 피한다. information_schema.TABLES.TABLE_ROWS는 추정치이고 실제 수량은 verify.sql의 COUNT(*)를 기준으로 한다.

Compose 관리:

```powershell
docker compose --env-file backend/.env -f backend/compose.yaml ps
docker compose --env-file backend/.env -f backend/compose.yaml logs --tail 50 mysql
docker compose --env-file backend/.env -f backend/compose.yaml stop
docker compose --env-file backend/.env -f backend/compose.yaml start
```

stop/start 또는 down/up은 명명된 볼륨 데이터를 보존한다. 볼륨 삭제 옵션은 일반 실행 절차에 포함하지 않는다. 비밀번호를 바꾸려면 저장된 DB 계정과 .env의 정합성을 맞추는 별도 작업이 필요하며, 인증 실패를 이유로 볼륨을 삭제하지 않는다.

## Seed 결정 사항

- FE 공연 ID c1/c2/c3, A-01~H-12 좌석 ID 유지
- 각 공연 VIP(A/B) 24석×154,000원, R(C~E) 36석×132,000원, S(F~H) 36석×99,000원
- 모든 공연 priceFrom=99,000원. FE Mock의 c2=88,000원/c3=110,000원 불일치를 새 DB seed에서 바로잡음. frontend는 수정하지 않음
- 288석 모두 AVAILABLE. Mock의 임의 BOOKED는 예매 원장 없이 복제하지 않음
- FE의 공연 시작 순간은 유지하고 DB에는 UTC로 저장, JSON도 오프셋 포함. 종료는 시작+3시간이라는 **데모 fixture**이며 실제 합의된 공연 길이가 아님
- 재실행 시 기존 공연/좌석을 업데이트하지 않으므로 기존 값에 불일치가 있으면 verify.sql 결과를 보고 별도 수정 결정

## 실행과 환경변수

Compose가 healthy가 된 뒤 실행한다.

```powershell
. ./backend/scripts/Invoke-Backend.ps1 -Task Run
```

스크립트는 .env의 지정된 애플리케이션 키만 읽고 코드로 실행하지 않는다. 기존 export 값이 우선이다. 직접 bootRun/java -jar를 실행하면 변수를 먼저 export해야 한다. Spring은 .env를 자동 로드하지 않는다.

| 변수 | 기본/용도 |
|---|---|
| DB_HOST | localhost |
| DB_PORT | 3308 |
| DB_USERNAME | demo_user |
| DB_PASSWORD | .env의 앱 비밀, Compose MYSQL_PASSWORD와 동일 |
| MYSQL_ROOT_PASSWORD | .env의 독립 root 비밀, Compose 초기 생성용. 앱 스크립트는 읽지 않음 |
| SERVER_PORT | 8080. 다른 서버가 사용 중이면 별도 포트 선택 |
| JPA_SHOW_SQL | false |
| HOLD_DURATION | PT5M, 선점 만료. 동일 선택 재요청으로 연장하지 않음 |
| TEST_PAYMENT_IDENTIFIERS | test-pay-shared-01,test-pay-shared-02,test-pay-shared-03 |
| BTS_ENABLED | false. 비활성 시 네트워크 호출 없이 Outbox 보존 |
| BTS_BASE_URL | 실제 발급 서버 origin. 원격은 HTTPS, 루프백 테스트만 HTTP |
| BTS_SERVICE_ID | 기본 demo-ticketing은 로컬 namespace. 실제 발급 서비스 ID를 사용자 생성 전에 설정 |
| BTS_API_KEY | 서버 전용 원문 비밀, .env/런타임에서만 관리 |
| DEMO_JOBS_ENABLED | true. 만료 정리와 Outbox 작업 활성화 |

JDBC URL은 localhost:3308/demo_ticketing_db다. DB명은 고정이고 DB_URL/DB_NAME override는 제공하지 않는다. Compose의 MySQL은 localhost에만 포트를 게시한다. BTS 호출 구조·서비스 인증키 정책과 Java API/DTO는 이번 DB 분리로 변경하지 않았다.

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/concerts
Invoke-RestMethod http://localhost:8080/api/v1/concerts/c1
Invoke-RestMethod http://localhost:8080/api/v1/concerts/c1/seats
. ./backend/scripts/Test-HttpApi.ps1
```

공통 envelope는 유지하며 조회/재시도는 COMMON_200, 선점/신규 예매는 COMMON_201이다. FE Concert 8개/Seat 7개와 Booking/SeatHold 필드를 유지한다. 없는 공연은 404/CONCERT_NOT_FOUND, 공백/길이 검증은 400/INVALID_REQUEST다. 데모 API는 Bearer, BTS 서버 간은 X-API-Key로 구분한다.

조회 HTTP 검사는 기존 필드와 400/404/405 및 미인증 쓰기 401을 확인한다. Test-BookingApi.ps1은 사용자 2명의 실제 선점·중복 방지·확정·취소·재판매·동시 HTTP를 확인하고 본 실행이 만든 hold/booking만 해제/취소한다. 보고서는 gitignored build/verification/http-smoke.json, booking-http-smoke.json에 기록하며 token/API Key는 포함하지 않는다.

frontend 코드는 변경하지 않았다. 실제 브라우저 흐름에는 AuthContext에서 로그인 응답 token을 tokenStore에 저장/로그아웃 시 삭제, PaymentPage에서 테스트 결제 식별자 선택, ticketing.confirmBooking의 paymentIdentifier 전송이 필요하다. 기존 FE의 ID-only 로컬 로그인 상태만으로 보호 API를 호출할 수 없다. 9종 행동 신호를 BTS로 직접 보내지 않는다.

## MySQL 통합 검증

SQL 적용·앱 계정 설정 후:

```powershell
. ./backend/scripts/Invoke-Backend.ps1 -Task MySqlTest
```

mysqlTest는 demo DB catalog/10개 테이블·기존 조회와 예약·BTS 계약을 검증한다. 예약 테스트는 실행별 임의 공연/사용자 행을 생성하고 해당 행만 FK 순서대로 정리한다. 기존 seed/BTS 데이터·테이블·볼륨을 초기화하지 않는다. Clock을 주입해 정확한 만료 경계를 확인하고 백그라운드 작업을 테스트 컨텍스트에서 비활성화한다. `build`에 자동 포함하지 않는다.

## 예매/취소 사용과 장애 정책

1. POST /api/v1/users에 `{"userId":"demo_user01"}` 전송 → data.token을 보관한다. 로컬 세션 성공은 BTS 등록 완료를 뜻하지 않는다.
2. `Authorization: Bearer <token>`과 `{"seatIds":["A-01"]}`로 /concerts/c1/seat-holds 호출.
3. `{"holdId":"h_...","paymentIdentifier":"test-pay-shared-01"}`로 /bookings 호출. 최초201, 동일 hold는 기존 Booking으로200.
4. /bookings/{id}/cancel은 전체 취소이며 반복해도 같은 결과다. /bookings/me는 본인 이력만 반환한다.
5. 이전으로 이동 시 DELETE /seat-holds/{id}. 만료/반복 해제도200, 다른 사용자 자원403.

선점 교체는 원자적이며 같은 선택 재요청은 만료를 연장하지 않는다. 만료(expiry<=now)는410, 타인 점유409. 새 선점에서 정리하고 5초 작업도 복구한다. 정리 전에도 조회상 만료 좌석은 AVAILABLE이다. 총액은 hold item 가격으로 계산하고, 서버 소요 시간은 hold.created_at~confirmed_at, IP는 연결 peer만 사용한다. X-Forwarded-For/Forwarded는 신뢰 설정이 없어 무시한다.

예매·좌석·티켓·Outbox는 한 트랜잭션이다. BTS 실패로 로컬 성공을 되돌리지 않으며 최초 요청 처리에서 외부 HTTP를 기다리지 않는다. Outbox는 고정 externalEventId/UTC 초/metadata를 저장하고 최초 전송 전에 사용자 UUID를 결합한 최종 payload를 한 번만 동결한다. worker 네트워크는 DB transaction 밖이다. 60초 lease로 중단 작업을 회수하고 동일 예매 BOOKING→CANCEL 순서를 지킨다.

BTS 연결/timeout/5xx는 같은 payload로 backoff 재시도(최대8회), 400/401/403/404/409 등은 FAILED 격리다. 200/COMMON_200와201/COMMON_201 모두 성공 처리한다. 장애 원인 해결 뒤 같은 ID/payload를 유지하는 운영 재처리가 필요하며 공개 관리 endpoint는 없다. key/UUID/ID를 자동 교체하지 않는다.

실제 BTS를 쓰려면 .env에 BTS_ENABLED=true와 발급받은 BTS_BASE_URL/BTS_SERVICE_ID/BTS_API_KEY를 설정한다. service ID를 바꿔 기존 매핑을 자동 재사용하지 않는다. 현재 실제 발급 정보가 없어 **실제 BTS 인스턴스 수신은 미검증**이며, 전용 HTTP 계약 서버로 등록·저장 후503·409·같은 body 재전송을 확인했다.

## 실행 결과(2026-11-01, 예매 구현 후)

- 기존 공연3개/좌석288개 및 독립 MySQL 3308/볼륨 유지, 추가 지원 테이블 포함 총10개
- Gradle build 및 일반 테스트11개, MySQL 통합14개 통과
- 동시 동일 좌석: 한 사용자만 성공. 같은 hold 동시 확정: 신규201+기존200으로 같은 Booking. 중복/동시 취소: 이벤트1개
- 실패한 Outbox 로컬 저장까지 포함한 전체 롤백, 정확한 만료 경계, 소유권/세션 만료, UTC/KST 변환 회귀 확인
- 전용 HTTP 서버(8081, HOLD_DURATION=PT10S)에서 실제 예매 시나리오29건(만료 포함) 및 조회 회귀13건 통과
- 실 HTTP 검증에서 만든 예매3건은 모두 취소, 좌석288석 AVAILABLE로 복구. BOOKING3/CANCEL3 Outbox는 BTS 비활성 상태라 PENDING으로 보존
- BTS 계약 서버: 사용자 UUID 매핑, 세 enum 계약, 저장 후503→동일 bytes 재전송→200,409 격리 및 접속 거절 확인
- 기존 BTS DB/컨테이너·frontend 코드 변경 없음. commit/push 없음

재실행:

```powershell
. ./backend/scripts/Initialize-Database.ps1 -Apply
. ./backend/scripts/Invoke-Backend.ps1 -Task Build
. ./backend/scripts/Invoke-Backend.ps1 -Task MySqlTest
. ./backend/scripts/Test-BookingApi.ps1
```

실 HTTP 만료 검사는 별도 테스트 서버를 HOLD_DURATION=PT10S로 실행한 뒤 `Test-BookingApi.ps1 -BaseUrl http://localhost:8081 -CheckExpiry`를 사용한다. 기본5분 서버에서 기다리는 테스트는 거부한다. 검증 뒤 테스트 서버를 종료하고 기본 서버의5분 설정을 유지한다.

남은 작업: FE token/테스트 결제 연결, 실제 사용자 인증(현재 공유 데모), 실제 BTS 발급 설정과 수신 검증, FAILED 재처리 운영 도구, 부하에 따른 좌석별 잠금 세분화, 대기열·QR/검표·CHECK_IN 및 BTS Feature 추출. 실제 PG/환불은 별도 범위다.

## 생성·수정 파일 목록

backend 하위는 모두 신규 파일이다(빌드 산출물 제외).

```text
backend/
  .gitignore, .env.example, compose.yaml, README.md
  build.gradle, settings.gradle, gradlew, gradlew.bat
  gradle/wrapper/gradle-wrapper.jar
  gradle/wrapper/gradle-wrapper.properties
  db/00-create-database.sql, schema.sql, seed.sql, verify.sql, 02-booking-support.sql
  scripts/MySql-Tools.ps1, Initialize-Database.ps1, Invoke-Backend.ps1, Test-HttpApi.ps1, Test-BookingApi.ps1
  src/main/resources/application.properties
  src/main/java/com/bts/demoticketing/
    DemoTicketingApplication.java
    common/response/ApiResponse.java, ResponseCode.java
    common/exception/ApiException.java, GlobalExceptionHandler.java
    concert/controller/ConcertController.java
    concert/service/ConcertService.java
    concert/repository/ConcertRepository.java
    concert/entity/Concert.java
    concert/dto/ConcertResponse.java
    seat/service/SeatQueryService.java
    seat/repository/ConcertSeatRepository.java
    seat/entity/ConcertSeat.java, SeatGrade.java, SeatStatus.java
    seat/dto/SeatResponse.java
  src/test/java/com/bts/demoticketing/concert/
    controller/ConcertControllerTests.java
    ConcertApiMySqlTests.java
```

수정 문서: docs/api-spec.md, docs/erd.md, docs/bts-integration.md에 1단계 구현 및 전용 DB 검증 완료 범위를 표시했다. frontend 및 별도 BTS 저장소는 변경하지 않았다.

참고: Java/Gradle 호환 범위는 [Spring Boot 공식 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), CHECK 관련 제한은 [MySQL 8.0 공식 문서](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)를 확인했다. 적용 버전은 인접 BTS 프로젝트와 같은 4.1.1로 고정했다.

Compose 최초 초기화와 기존 볼륨 동작은 [MySQL 공식 이미지 문서](https://hub.docker.com/_/mysql)를 따른다. healthcheck는 앱 계정 인증과 concerts 조회를 확인하며 준비 전 연결을 구분한다.

2단계 추가 파일:
- config/BookingConfiguration.java
- user/entity/DemoUser.java, repository/DemoUserRepository.java, service/DemoAuthService.java, controller/DemoUserController.java
- booking/entity/ReservationRecords.java, dto/BookingDtos.java, repository/ReservationRepository.java, service/ReservationService.java, service/BookingJobs.java, controller/BookingController.java
- integration/bts/BtsSettings.java, BtsClient.java
- outbox/OutboxRepository.java, OutboxTransactions.java, OutboxDeliveryService.java
- test: booking/ReservationMySqlTests.java, integration/bts/BtsClientContractTests.java
- 기존 조회 Controller/Service·공통 오류 코드·설정·테스트·SQL 적용 스크립트 및 세 설계 문서 보완
