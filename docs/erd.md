# Demo Ticketing DB 명세

## DB 경계와 시간

Demo는 `demo_ticketing_db`를 사용하며 기본 MySQL 포트는 localhost:3308이다. BTS의 localhost:3307/fairticket_db와 계정·볼륨·스키마를 공유하지 않는다. 테이블은 InnoDB이고 기본 문자 집합·정렬은 utf8mb4/utf8mb4_0900_as_cs다. opaque 식별자와 token hash에는 명시된 ascii/ascii_bin을 사용한다.

DATETIME에는 시간대가 없으므로 애플리케이션과 JDBC에서 UTC 원천으로 읽고 쓴다. 업무 시각은 DATETIME(6), 이벤트 occurred_at은 초 단위다. BTS 전송 occurredAt은 UTC ISO 8601 Z 문자열이며 metadata.recordedAt은 소수 초를 포함할 수 있다.

## 관계

```mermaid
erDiagram
  demo_users ||--o{ demo_sessions : authenticates
  demo_users ||--o{ seat_holds : owns
  concerts ||--o{ concert_seats : contains
  concerts ||--o{ seat_holds : reserves
  demo_users ||--o{ reservation_user_concert_guards : serializes
  concerts ||--o{ reservation_user_concert_guards : serializes
  seat_holds ||--o{ seat_hold_items : contains
  concert_seats ||--o{ seat_hold_items : history
  seat_holds ||--o| bookings : confirms
  demo_users ||--o{ bookings : owns
  concerts ||--o{ bookings : sells
  bookings ||--o{ booking_seats : contains
  concert_seats ||--o{ booking_seats : history
  booking_seats ||--o| admission_tickets : issues
  demo_users ||--o{ outbox_events : emits
  demo_users ||--o{ booking_attempts : starts
  concerts ||--o{ booking_attempts : targets
  seat_holds o|--o| booking_attempts : links
  bookings o|--o| booking_attempts : links
  booking_attempts ||--o{ booking_attempt_events : records
  outbox_events o|--o{ booking_attempt_events : delivers
```

concert_seats.active_hold_id/active_booking_id는 현재 점유를 가리키고 item 테이블은 과거 이력을 유지한다. Outbox의 aggregate_id는 업무 그룹을 구분하는 문자열이며 FK가 아니다.

## 업무 테이블

| 테이블 | 주요 컬럼 | 무결성·조회 인덱스 |
|---|---|---|
| demo_users | id,public_user_id,bts_service_id,bts_external_user_id,bts_user_id,created_at | 공개 ID UNIQUE, (service,external ID) UNIQUE, BTS UUID UNIQUE |
| demo_sessions | id,user_id,token_hash,expires_at | user FK, hash UNIQUE, expiry INDEX; 24시간 세션 |
| concerts | id,public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue | public_id UNIQUE, 종료>시작, 가격>=0, 0<=hue<360, 시작/공개 ID INDEX |
| reservation_user_concert_guards | user_id,concert_id | 복합 PK, user/concert FK; 동일 사용자의 공연별 예약 직렬화 |
| concert_seats | id,concert_id,seat_code,row_label,seat_number,grade,price,state,active_hold_id,active_booking_id,version | (공연,code)/(공연,row,number) UNIQUE, concert/hold/booking FK, (공연,state) INDEX |
| seat_holds | id,public_id,user_id,concert_id,status,expires_at,created_at | public_id UNIQUE, user/concert FK, 만료/소유자 INDEX |
| seat_hold_items | hold_id,concert_seat_id,price_snapshot | 복합 PK, hold/seat FK |
| bookings | id,public_id,user_id,concert_id,hold_id,status,total_price,공연 snapshot,booking_started_at,confirmed_at,booking_duration_seconds,payment_identifier,ip_address,created_at,cancelled_at | public_id/hold_id UNIQUE, user/concert/hold FK, 소유자·생성 시각 INDEX |
| booking_seats | booking_id,concert_seat_id,seat_code_snapshot,grade_snapshot,price_snapshot | 복합 PK, booking/seat FK |
| admission_tickets | id,public_id,booking_id,concert_seat_id,status,checked_in_at | public_id 및 (booking,seat) UNIQUE, booking_seats 복합 FK |

정확한 컬럼 정의·CHECK·FK 이름은 [schema.sql](../backend/db/schema.sql), [세션·Outbox 스키마](../backend/db/02-booking-support.sql), [동시성 스키마](../backend/db/03-seat-concurrency.sql)를 따른다. 03 스크립트는 기존 스키마의 guard 추가용이며 새 schema.sql에는 이미 반영되어 있다.

좌석 점유 CHECK는 AVAILABLE이면 두 참조가 NULL, HELD이면 hold만 존재, BOOKED이면 booking만 존재하도록 제한한다. hold 상태는 ACTIVE/RELEASED/EXPIRED/CONVERTED, 예매는 CONFIRMED/CANCELLED다. 같은 공연·좌석 관계와 현재 유효성은 서비스 트랜잭션에서도 검사한다. 취소·해제·만료 후에도 과거 item 이력은 보존한다.

예매의 제목·장소·공연 시작/종료 시각·좌석·가격은 snapshot이다. booking_duration_seconds는 사용 hold 생성부터 서버 확정까지의 0 이상 DECIMAL(18,6)이며 브라우저 체류 시간·대기열 시간을 대신하지 않는다. payment_identifier에는 허용된 테스트 식별자만 저장하고 ip_address에는 서버가 관측한 peer IP를 저장한다.

## 예매 시도 테이블

| 테이블 | 컬럼과 제약 |
|---|---|
| booking_attempts | attempt_id CHAR(36) ascii_bin PK; user_id/concert_id BIGINT NOT NULL FK; service_id VARCHAR(50); stage VARCHAR(30); status VARCHAR(20); sequence_no BIGINT; hold_id/booking_id nullable BIGINT FK 및 각각 UNIQUE; started_at DATETIME(6); ix_attempt_owner(user_id,concert_id,started_at) |
| booking_attempt_events | id BIGINT AUTO_INCREMENT PK; attempt_id CHAR(36) ascii_bin FK; transition_id VARCHAR(100) ascii_bin; sequence_no BIGINT; request_fingerprint CHAR(64); metadata JSON; occurred_at DATETIME; outbox_id nullable BIGINT FK; UNIQUE(attempt_id,transition_id), UNIQUE(attempt_id,sequence_no) |

request_fingerprint는 전이 내용의 SHA-256이다. 동일 transition의 같은 내용은 재사용하고 다른 내용은 충돌한다. sequence는 attempt 행 잠금 안에서 증가한다. outbox_id가 NULL인 journal은 주기 작업에서 Outbox에 연결한다. 업무 확정과 journal 완료는 예약 트랜잭션에서 원자적으로 처리하고 원격 전송은 분리한다.

## Outbox

outbox_events는 id PK와 user_id FK, service_id, external_event_id, business_transition_id, aggregate_id, event_type, occurred_at_utc, metadata JSON, payload LONGTEXT, delivery_status, lease_token, locked_until, retries, next_attempt_at, bts_event_id, last_error_code, created_at, sent_at을 저장한다.

- UNIQUE(service_id,external_event_id), UNIQUE(business_transition_id,event_type).
- ck_outbox_type: BOOKING/CANCEL/CHECK_IN/BOOKING_ATTEMPT.
- ck_outbox_status: PENDING/IN_FLIGHT/RETRY/SENT/FAILED.
- ck_outbox_payload: payload IS NULL OR JSON_VALID(payload).
- 최종 payload는 UUID 결합 후 한 번 고정한다. LONGTEXT는 재전송 body를 보존하고 metadata JSON은 원천 구조를 유지한다.
- lease 기반 claim과 업무 그룹 순서, 재시도 정책은 [BTS 연동 명세](bts-integration.md)를 따른다.

## Redis 저장 구조

대기열용 MySQL queue_entries/admissions 테이블은 없다. `QUEUE_KEY_PREFIX:{공연 ID의 Base64URL}:` 아래 waiting/waiting-delayed, deadlines, admitted, expired, sequence를 사용한다. 같은 공연 키는 Redis Cluster hash tag를 공유한다. CAPTCHA challenge/grant/replay도 같은 공연 namespace에서 TTL을 가진 HASH로 관리한다. grant는 사용자·공연·정책 action에 묶이며 등록 시 원자적으로 소비한다.

## 스키마 적용

Hibernate ddl-auto=none, spring.sql.init.mode=never다. Compose는 새 빈 볼륨에서 schema/seed/02/03을 초기화하며 기존 볼륨을 자동 마이그레이션하지 않는다. [04-booking-attempts.sql](../backend/db/04-booking-attempts.sql)은 MySQL 8.0.16+에서 Demo DB에 한 번만 적용한다. 두 attempt 테이블 생성과 Outbox CHECK 교체를 포함하며 반복 실행용 IF NOT EXISTS 스크립트가 아니다.

적용 전 실제 DB 이름·기존 테이블·CHECK·백업을 확인하고 쓰기 작업을 중지한다. DDL은 개별 자동 commit되며 CHECK 교체는 메타데이터 잠금을 얻는다. 일부 실패 시 자동 재실행·임의 복구하지 말고 현재 스키마를 먼저 확인한다. 이미 적용된 DB에 재실행하거나 기존 업무 데이터·BTS 스키마를 초기화하지 않는다. 적용 후 BOOKING_ATTEMPTS_ENABLED를 명시적으로 활성화한다.
