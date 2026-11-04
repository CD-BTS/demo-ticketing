# 데모 티켓팅 프론트엔드 분석 및 백엔드 설계

작성일: 2026-11-01. 범위: FE 분석과 백엔드 설계 및 구현. 현재 계약은 아래 **Issue #17 입장 권한 검증**, **Issue #15 Redis FIFO 대기열**, **2단계 예매 구현**과 [backend 실행 안내](../backend/README.md)를 우선 적용한다. 이후의 초기 FE 분석·가정은 현재 코드와 비교하기 위한 기록이다.

### Issue #17 입장 권한 검증 (현재 구현)

`POST /api/v1/concerts/{concertId}/seat-holds`는 기존 Bearer 세션의 내부 사용자 ID로 **해당 공연의 Redis 입장 권한**을 검사한다. 요청 body는 기존 `{"seatIds":["A-01"]}` 그대로이며 userId나 별도 입장 토큰을 받지 않는다. 브라우저 sessionStorage는 권한 근거가 아니다.

| Redis 조회 결과 | 좌석 선점 응답 |
|---|---|
| 해당 공연 ADMITTED | 기존 선점 검증을 거쳐 201/COMMON_201 |
| NOT_JOINED / WAITING / EXPIRED | 403/QUEUE_NOT_ADMITTED |
| 다른 공연에서만 ADMITTED | 요청한 공연 권한이 없으므로 403/QUEUE_NOT_ADMITTED |
| ADMITTED 멤버가 남아 있어도 입장 deadline 만료 | Lua가 만료 정리한 뒤 403/QUEUE_NOT_ADMITTED |
| Redis 연결·명령 실패 | 503/SERVICE_UNAVAILABLE, 선점 생성 우회 없음 |

```json
{"success":false,"code":"QUEUE_NOT_ADMITTED","data":null,"message":"대기열 입장 권한이 없습니다. 대기열 상태를 확인해 주세요."}
```

검사는 `ReservationService.hold()`의 공연 행 잠금 획득 직후, 선점 데이터 변경 전에 수행한다. 생성·교체·동일 좌석 선점 재요청에도 적용한다. 미인증은 기존 401, 잘못된 입력은 기존 400, 없는 공연은 기존 404를 유지한다. 기존 `QueueService`/`QueueStore`의 status 연산을 재사용하며 Redis TIME 기준 `expiresAt <= now` 정리 및 FIFO 승격 후 **현재 결과**를 판정한다. 따라서 조회 과정에서 FIFO 순서대로 승격된 사용자는 ADMITTED로 허용될 수 있다. Lua·TTL·정원 정책은 변경하지 않는다. 권한은 이 검사 시점에 판정하며 Redis와 MySQL을 하나의 분산 트랜잭션으로 묶지는 않는다.

`POST /api/v1/bookings`는 **유효한 본인 소유 hold**를 기준으로 확정하며 Redis를 다시 검사하지 않는다. 선점 생성 경로에서 입장 권한을 강제하므로 이후 입장 TTL 만료·대기열 이탈·Redis 장애가 이미 유효한 hold를 무효화하지 않는다. 별도 MySQL 입장 기록이나 입장 토큰은 추가하지 않는다. 배포 전 생성된 유효한 hold도 기존 만료까지 이 정책을 따른다.

본인 hold 누락/만료/해제는 기존 410/HOLD_EXPIRED, 타인 hold는 기존 403/FORBIDDEN을 유지한다. 이미 확정한 동일 hold의 재요청은 기존 예매를 반환하는 멱등 처리를 유지하며 이후 hold deadline 경과만으로 재확정을 거부하지 않는다. 기존 충돌 검증과 트랜잭션·잠금·롤백·hold_id UNIQUE는 유지한다.

선점 해제, 예매 취소, 본인 예매 내역에는 대기열 검사를 추가하지 않는다. 공연 목록·상세·좌석 목록은 기존 공개 정책을 유지한다. 구현 완료 범위는 FIFO 및 선점 입장 권한 강제이며, BTS 이상 탐지 기반 차등 대기열(#16), FE 연동, QR/검표는 후속 범위다. [검증 및 직접 PowerShell API 호출](../backend/scripts/README-QueueAdmission.md)을 참고한다.

### Issue #15 Redis FIFO 대기열 (현재 계약)

대기열 API 계약은 아래와 같으며 좌석 선점에 대한 입장 권한 강제는 위 #17 현재 계약을 따른다. BTS 점수·차등 순위·CAPTCHA는 사용하지 않는다.

| Method / URL | 인증 / Request | 정상 응답 |
|---|---|---|
| POST /api/v1/concerts/{concertId}/queue | 기존 데모 세션 `Authorization: Bearer <token>`, body 없음 | 신규 및 만료 후 재진입 201/COMMON_201, 활성 중복 200/COMMON_200 |
| GET /api/v1/concerts/{concertId}/queue/me | 동일 Bearer, body/query 없음 | 200/COMMON_200, 본인 상태 |
| DELETE /api/v1/concerts/{concertId}/queue/me | 동일 Bearer, body 없음 | 200/COMMON_200, data:null. 반복·미등록 이탈도 동일 |

사용자는 검증된 세션의 내부 ID로 식별하며 요청으로 userId를 받지 않는다. concertId는 비어 있지 않은 최대 50자 공개 ID다. 신규 진입 시 빈자리가 있으면 즉시 ADMITTED가 될 수 있다.

POST 응답 예시(대기 중):

```json
{"success":true,"code":"COMMON_201","data":{"status":"WAITING","position":1,"expiresAt":"2026-11-04T03:30:00Z"},"message":"요청이 성공했습니다."}
```

GET 응답 예시(입장 허용):

```json
{"success":true,"code":"COMMON_200","data":{"status":"ADMITTED","position":0,"expiresAt":"2026-11-04T03:05:00Z"},"message":"요청이 성공했습니다."}
```

GET 응답 예시(만료):

```json
{"success":true,"code":"COMMON_200","data":{"status":"EXPIRED","position":null,"expiresAt":null},"message":"요청이 성공했습니다."}
```

DELETE 응답:

```json
{"success":true,"code":"COMMON_200","data":null,"message":"요청이 성공했습니다."}
```

| 오류 HTTP / code | 적용 |
|---|---|
| 400 / INVALID_REQUEST | 잘못된 concertId. 기존 검증 오류는 data.errors 배열 사용 |
| 401 / UNAUTHORIZED | Bearer 누락·불량·중복 헤더·만료 |
| 404 / CONCERT_NOT_FOUND | 존재하지 않는 공연(세 API 공통) |
| 503 / SERVICE_UNAVAILABLE | Redis 접속/명령 실패; 성공으로 우회하지 않음 |
| 500 / INTERNAL_SERVER_ERROR | 예상하지 못한 오류; 내부 상세 비공개 |

오류 예시:

```json
{"success":false,"code":"SERVICE_UNAVAILABLE","data":null,"message":"현재 서비스를 이용할 수 없습니다."}
```

상태 전이: `NOT_JOINED → WAITING → ADMITTED → EXPIRED`. 빈자리에서는 WAITING을 거쳐 즉시 ADMITTED 응답. WAITING도 만료될 수 있다. DELETE는 모든 본인 상태를 지워 NOT_JOINED가 되며 재진입하면 새 순번으로 맨 뒤에 선다. 활성 상태의 POST 재요청은 동일 등록과 만료를 유지한다. EXPIRED 재진입은 새 등록이다. GET은 등록하지 않는다. 미등록·이탈·만료기록 보존기간 종료는 NOT_JOINED(position/expiresAt null)이다.

WAITING position은 **현재 대기자 중 1부터 시작하는 순번**이며 ADMITTED는 0이다. 조회 간 만료/이탈로 줄어들 수 있다. expiresAt은 해당 상태의 절대 UTC 만료 시각이며 Redis TIME을 기준으로 한다. 동시 진입 순서는 Redis 스크립트 실행 순서다(클라이언트 전송 시각 기준이 아님).

설정: QUEUE_CAPACITY=100, QUEUE_WAIT_TTL=PT30M, QUEUE_ADMIT_TTL=PT5M, QUEUE_EXPIRED_RETENTION=PT10M. 양수만 허용하며 TTL은 ISO-8601 Duration이다. 대기는 최초 등록부터, 입장은 승격부터 계산하며 GET/중복 POST로 갱신하지 않는다. 만료 판정은 `expiresAt <= Redis 현재 시각`. 정원은 공연별이며 설정 변경 시 모든 앱 인스턴스에 같은 값을 적용해야 한다. 정원을 줄이면 기존 입장자는 만료까지 유지하고 신규 입장을 멈춘다.

**요청 기반 진행:** 모든 대기열 요청에서 Lua가 만료 정리·입장 슬롯 반환·FIFO 승격을 원자적으로 수행한다. 별도 스케줄러/키 만료 알림은 없다. 요청이 없으면 승격하지 않으며 다음 요청 때 정리 후 남은 정원만큼 승격한다. 프론트는 약 2초 간격 GET을 권장한다. 입장 TTL은 실제 승격 시점부터 시작한다. ADMITTED가 자리를 점유하며 예매 완료와 자동 연결되지 않는다. DELETE 또는 만료가 슬롯을 반환한다.

Redis 키는 `QUEUE_KEY_PREFIX:{base64url(concertId)}:*`이며 기본 접두사는 demo-ticketing:queue다. waiting ZSET(score=INCR 순번), deadlines ZSET(대기 만료), admitted ZSET(입장 만료), expired ZSET(만료 기록 보존기한), sequence STRING을 사용한다. List보다 중복 확인·임의 이탈·순번 조회에 적합하다. 같은 공연의 키는 Redis Cluster hash tag를 공유한다. Lua 한 번으로 검사·등록·정리·승격·조회하여 다중 인스턴스에서도 중복 등록과 정원 초과를 막는다. 만료 정리는 만료자 수에 비례하므로 대규모 트래픽에서는 배치 정리 방식 검토가 필요하다.

모든 공연 키에 `max(대기TTL,입장TTL)+만료기록보존기간`의 idle TTL을 적용한다. 요청 때만 갱신되며 개별 사용자의 TTL은 연장되지 않는다. 장기 유휴 공연의 키가 정리되면 NOT_JOINED가 된다. 개발 Redis는 전용 demo-ticketing-redis 컨테이너(localhost:6380), demo-ticketing-redis-data 볼륨과 AOF를 사용한다. 재시작 후에도 저장된 상태를 복원하며 절대 만료 시각은 그대로 적용한다. 기본 everysec 정책에서는 비정상 종료 시 최근 약 1초의 쓰기 유실 가능성이 있다. MySQL 업무 데이터와는 분리된다.

현재 FE의 WaitingRoom은 mock이 기본이고 real adapter는 ticketId 기반 `/queue/{ticketId}`를 가정한다. 이번 `/concerts/{concertId}/queue/me`와 후속 연동이 필요하며 이번 이슈에서 FE/UI를 변경하지 않는다. sessionStorage의 입장 기록은 서버 권한이 아니다.

### 2단계 예매 구현 (이력)

공연/좌석 공개 조회를 유지하고 선점·확정·취소·본인 내역·데모 세션을 추가했다. 실제 카드 승인/환불, Redis 대기열, QR 발급/검표, CHECK_IN 전송은 이번 구현에 포함하지 않는다. BTS는 서버 전용 X-API-Key로 호출하며 브라우저의 기존 9종 /events 신호는 중계하지 않는다.

| API | 인증/요청 | 정상 응답 |
|---|---|---|
| POST /api/v1/users | 데모 ID `{"userId":"demo_user01"}`. 영문/숫자/_/- 3~30자 | 200/COMMON_200, data={userId,token,expiresAt} |
| POST /api/v1/concerts/{concertId}/seat-holds | Bearer, `{"seatIds":["A-01","A-02"]}` | 201/COMMON_201, 기존 SeatHold DTO |
| DELETE /api/v1/seat-holds/{holdId} | Bearer, body 없음 | 200/COMMON_200, data:null |
| POST /api/v1/bookings | Bearer, `{"holdId":"h_...","paymentIdentifier":"test-pay-shared-01"}` | 최초 201/COMMON_201, 동일 hold 재요청 200/COMMON_200, 기존 Booking DTO |
| POST /api/v1/bookings/{bookingId}/cancel | Bearer, body 없음 | 200/COMMON_200, CANCELLED Booking. 재취소도 같은 결과 |
| GET /api/v1/bookings/me | Bearer, query/body 없음 | 200/COMMON_200, 본인 Booking[] 생성 역순 |
| GET /api/v1/concerts/{concertId}/seats | 공개 가능, Bearer가 있으면 검증·본인 선점 표시 | 기존 Seat[]; 유효한 본인 HELD만 heldByMe=true |

세션은 서버가 무작위 token을 발급하고 SHA-256 hash만 DB에 저장하며 24시간 유효하다. ID 재입력으로 같은 데모 신원을 시작할 수 있는 **공유 데모 식별 방식**이지 실사용 계정의 소유권 인증은 아니다. 실서비스 공개 전 별도 사용자 인증이 필요하다. Bearer 누락/불량/중복 헤더/만료는 401/UNAUTHORIZED, 타인의 hold/booking은 403/FORBIDDEN. userId를 요청 body로 보내 소유권을 바꿀 수 없다. token은 BTS UUID/API Key와 별개다. POST /users는 로컬 세션 시작이며 BTS 등록·매핑은 전송기에서 수행한다.

선점은 최대 4석, 중복 코드 금지. 기본 5분(HOLD_DURATION)이며 같은 사용자·공연의 동일 선택 재요청은 같은 hold와 만료를 유지한다. 변경 요청은 원자적으로 기존 선점을 교체하고 실패 시 기존 것을 유지한다. 없는 좌석 404/SEAT_NOT_FOUND, 중복/빈/5석 이상은 400(중복은 INVALID_SEAT_SELECTION, DTO 검증은 INVALID_REQUEST), 타인 선점·예매 충돌은 409/SEAT_ALREADY_TAKEN이다.

만료는 expires_at <= 서버 현재 시각. 새 선점 시 해당 공연의 만료 hold를 정리하고 5초 주기 작업도 상태를 복구한다. 좌석 조회는 정리 작업이 지연돼도 만료된 HELD를 AVAILABLE로 표시한다. 만료/해제 hold 확정은 410/HOLD_EXPIRED이며 BOOKING/CANCEL 이벤트를 만들지 않는다. 없는 hold 해제/반복 해제도 200이며 타인의 존재하는 hold는 403이다.

paymentIdentifier는 기본 test-pay-shared-01/02/03 중 하나(TEST_PAYMENT_IDENTIFIERS 설정)이며 누락/형식 오류/미허용 값은 400/INVALID_REQUEST다. 동일 hold가 이미 확정됐으면 기존 Booking을 반환하고 새 IP/시각/결제 식별자로 변경하거나 새 이벤트를 생성하지 않는다. 총액은 선점 item snapshot에서 서버가 계산한다. 취소된 hold의 재확정도 기존 CANCELLED Booking을 반환한다. 부분 취소/실제 PG 처리는 없다.

모든 쓰기 연산은 동일 공연 행의 SELECT FOR UPDATE를 먼저 획득한 후 트랜잭션 안에서 현재 상태를 재조회한다. 이 공연 단위 직렬화와 hold_id UNIQUE로 다른 사용자/다중 인스턴스의 이중 선점·확정을 막는다. 높은 부하에서는 좌석별 잠금으로 세분화할 후속 여지가 있다. 예매/좌석/티켓/Outbox 저장 중 하나라도 실패하면 전부 롤백된다. 재취소는 이미 재판매된 좌석을 해제하지 않는다.

BTS 장애 정책: 예매·취소와 같은 트랜잭션에 이벤트 원천을 저장하고, 네트워크는 별도 전송기가 처리한다. BTS 실패로 성공한 로컬 예매를 취소하거나 예매 API에 upstream 인증 401을 반환하지 않는다. BTS_ENABLED=false(기본)이면 이벤트는 PENDING으로 보존되며 전송하지 않는다. 실제 연결에는 발급받은 서비스 설정이 필요하다. 상세 재시도·불변 payload·운영 복구는 [BTS 연동 설계](bts-integration.md)의 2단계 구현 절을 따른다.

현재 frontend는 token 응답을 저장하지 않고 paymentIdentifier도 보내지 않으므로 AuthContext/client와 PaymentPage/ticketing 입력 연동이 후속으로 필요하다. 이번 작업에서 FE 코드는 수정하지 않는다. GET /users/{id} 상세 facade와 POST /events 텔레메트리는 아직 구현하지 않았다.

### 1단계 구현 당시 상태(이력)

- 구현: GET /api/v1/concerts, GET /api/v1/concerts/{concertId}, GET /api/v1/concerts/{concertId}/seats. MySQL Repository 조회, COMMON_200 envelope, 경로 검증과 공통 예외 처리.
- 이번 단계 결정: 인증/대기열 없이 조회 허용. 아래 기존 ‘인증 필수 제안’은 아직 적용하지 않았다. heldByMe는 항상 false이며 상태 자체는 DB에서 읽는다.
- Java 17 / Spring Boot 4.1.1 / Gradle 9.7.1. 실제 인접 BTS 소스의 레이어·record DTO·common 응답/예외·db/schema.sql 방식을 참고했다. Flyway 없이 ddl-auto=none.
- DB는 독립 Compose의 demo-ticketing-mysql(MySQL 8.0, localhost:3308, demo_user, 별도 demo-ticketing-mysql-data 볼륨)로 전환했다. 핵심 8개 테이블·공연 3개·좌석 288개를 적용하고 schema/seed 재실행의 중복 방지도 확인했다. 기존 BTS 컨테이너(3307)는 유지한다.
- Gradle build, MVC 7개·MySQL 통합 3개 테스트와 실제 HTTP 13건 검증 완료. 조회는 200, 예매/선점은 아직 미구현으로 404임을 확인했다. 앱은 8080에서 3308 DB에 연결한다. API/DTO 및 BTS 연동 계약은 그대로 유지한다.
- frontend 및 BTS 소스는 수정하지 않았다. 사용자·선점·예매·취소·대기열·QR·BTS 전송은 구현하지 않았다.

## 1. 근거와 명세의 확정 수준

- **[코드 확인]** 실제 함수 호출, TypeScript 타입, 화면 동작으로 확인한 사실이다. 서버가 해당 규격을 구현했다는 뜻은 아니다.
- **[FE 가정]** 프론트엔드에 존재하지만 주석에서 미확정으로 명시한 계약이다. 이번 문서는 이 경로와 필드명을 우선 유지한다.
- **[신규 제안]** 이번 분석에서 제안하는 서버 정책·구조다. 구현 전 팀 합의가 필요하다.
- **[협의 필요]** 코드만으로 결정할 수 없는 사항이다.
- **[BTS 확정]** 사용자가 제공한 실제 사용자·이벤트 API 명세에 따른 서버 간 계약이다. [BTS 연동 설계](bts-integration.md)에 상세 계약과 오류·시간·재시도 규칙을 기록한다.
- **[연동 결정]** eventType은 BOOKING/CANCEL/CHECK_IN 유지. Feature 원천은 metadata로 전달하며 데모는 Feature를 계산하지 않는다. metadata 키·소요 시간 경계·관람 단위의 구체안은 연동 결정과 구별해 설계안으로 표시한다.

초기 FE 분석 대상: `src/api/` 5개 파일, `auth/AuthContext.tsx`, `pages/` 7개 파일, `hooks/useBehavior.ts`, `components/` 5개 파일, `risk/` 2개 파일, `App.tsx`, `main.tsx`, `styles.css`, `index.html`, `.env.example`, `package.json` 및 잠금 파일, TypeScript/Vite 설정. 잠금 파일은 의존성 자료이며 API 계약의 근거로 사용하지 않았다. 루트 README도 대조했다. 당시 backend는 비어 있었으며 이후 1단계 구현을 추가했다. BTS 소스는 1단계에서 인접 저장소를 확인했고 외부 Notion/PDF 원문은 확인하지 않았다.

`bts.ts` 주석의 “구현 완료”와 별개로, 확보한 실제 BTS 명세는 현재 FE 요청과 일치하지 않는다. 사용자 등록은 serviceId/externalUserId, 사용자 식별은 반환 UUID, 인증은 서버 간 X-API-Key이며 이벤트는 BOOKING/CANCEL/CHECK_IN만 허용한다. 티켓팅·대기열 경로는 여전히 FE 가정이다. **4절은 브라우저→데모 계약**, 실제 **데모→BTS 계약은 [BTS 연동 설계](bts-integration.md)**로 분리한다.

## 2. 페이지 및 사용자 흐름

모든 소스 경로는 별도 표시가 없으면 `frontend/` 기준이다.

| 경로/파일 | 구현 기능 | API 및 내부 처리 |
|---|---|---|
| 로그인 전 모든 경로: `pages/LoginPage.tsx` | ID 입력 및 시작 | trim 후 영문·숫자·`_`·`-` 3~30자 검사 → `registerUser` → `fds.userId` 저장. 비밀번호 없음 |
| `/`: `pages/ConcertListPage.tsx` | 공연 카드 목록 | `listConcerts`. 검색·필터·페이지네이션 없음 |
| `/concerts/:id`: `pages/ConcertPage.tsx` | 대기열/좌석 화면 전환 | 사용자·공연별 sessionStorage 입장 기록 확인 |
| `pages/WaitingRoom.tsx` | 대기 진입·순번·뒤 인원·취소 | 공연 조회, `join`, 250ms마다 `status`. position=0이면 10ms 후 입장. 입장 전 unmount 시 `leave` 시도 |
| `pages/ConcertDetailPage.tsx` | 공연 상세·좌석 선택·합계·선점 | 공연/좌석 병렬 조회. 내 선점 좌석 복원. 최대 4석 선택. “예매하기”에서만 선점 생성 |
| `/concerts/:id/payment`: `pages/PaymentPage.tsx` | 선점 타이머·가상 카드 입력·완료 | router state의 hold 필요. 공연/좌석 재조회. 15자리 임의 숫자 입력 → 1.2초 대기 → 예매 생성 |
| `/my`: `pages/MyPage.tsx` | 예매 내역·취소·계정 등급 | `listMyBookings`, `cancelBooking`, 브라우저 신용 점수 계산 |
| `components/QrModal.tsx` | QR 조회 및 표시 | 예매별 1회 조회. null이면 발급 전. Escape/배경 클릭 닫기. 주기적 갱신은 TODO |
| `components/Header.tsx` | 목록/마이페이지 이동·로그아웃 | 사용자 ID만 삭제하고 `/` 이동. 서버 로그아웃 호출 없음 |

`App.tsx`는 userId가 없으면 모든 경로에 로그인 화면을 표시한다. 로그인 후 알 수 없는 경로는 `/`로 이동한다. `main.tsx`는 BrowserRouter, AuthProvider, React.StrictMode를 사용한다. `SeatMap.tsx`는 AVAILABLE 또는 heldByMe인 좌석 등을 클릭 가능하게 표시하고 선택 배열은 화면 state로 관리한다. `format.ts`, CSS, HTML은 금액/날짜/QR/포스터 표시를 담당하며 추가 업무 API 호출은 없다.

### 2.1 정상 예매와 예외 흐름

1. 사용자 ID 등록 → 브라우저에 ID 저장 → 공연 목록.
2. 공연 클릭 → 대기열 진입 → 순번 0 → 15분짜리 브라우저 입장 기록 저장 → 좌석 화면.
3. 공연·좌석 조회 → 최대 4석 선택 → 선점 요청 → 반환된 hold를 router state로 결제 화면에 전달.
4. 가상 카드 15자리 입력 → 모의 지연 → `{holdId}`로 예매 생성 → 완료 화면 → 마이페이지.
5. 마이페이지에서 취소 → 예매 목록 재조회. QR 버튼은 별도 모달에서 토큰 조회.

선점 실패 시 선택을 비우고 좌석을 재조회한다. 결제 화면의 “이전으로”는 선점 해제를 시도하며 실패해도 이동한다. 다른 이동·탭 종료에서 선점 해제를 보장하지 않으므로 서버 만료 처리가 필요하다. 결제 페이지에 hold state 없이 직접 접근하면 좌석 화면으로 이동한다. hold 복원 API는 없다. router state는 서버의 선점 유효성 근거가 될 수 없다.

### 2.2 현재 인증의 공백

- `AuthContext.tsx`는 등록 응답을 인증 토큰으로 사용하지 않고 입력 ID만 저장한다. 기존 ID 재입력도 같은 등록 함수를 호출하므로 등록 중복 처리 정책이 중요하다.
- `api/client.ts`에는 `fds.token` 저장소와 Bearer 헤더 처리가 있지만 **tokenStore.set/clear 호출부가 없다**. 자동 토큰 발급·갱신·401 재로그인도 없다. 로그아웃은 기존 token을 지우지 않는다.
- 실제 티켓팅/대기열 adapter는 함수 인자의 userId를 전송하지 않는다. 서버가 인증된 사용자를 식별할 방법을 먼저 합의해야 한다.
- ID를 아는 것만으로 기존 계정에 접근하는 현재 UX는 계정 소유 증명이 아니다. 실제 사용자 인증과 데모용 ID 세션을 구분해야 한다.

## 3. 공통 API 규칙

### 3.1 요청과 인증

**[코드 확인]** 모든 HTTP 요청은 `api/client.ts`의 fetch를 사용한다.

- URL: `${VITE_API_BASE_URL ?? ''}/api/v1` + adapter path.
- `Content-Type: application/json`; token이 있으면 모든 요청에 `Authorization: Bearer <token>` 추가.
- 본문이 없는 요청은 JSON `{}`도 보내지 않는다. 현재 query parameter는 없다.
- 개발 서버 `/api`는 `http://localhost:8080`으로 프록시한다. BTS와 티켓팅에 서로 다른 base URL을 설정하는 기능은 없다.
- 쿠키 인증을 위한 `credentials: 'include'` 설정은 없다. 별도 origin 쿠키 방식 선택 시 FE 수정이 필요하다.

아래 4절의 **데모 외부 인증 필요 여부는 [신규 제안] 정책**이다. BTS User/Event의 X-API-Key 인증은 확정이며 데모 서버만 사용한다. 현재 FE 로그인 화면 제한만으로 서버 인증이 구현된 것은 아니다. “본인”은 request body의 userId가 아닌 서버 인증 주체로 판단한다. 모든 보호 API의 공통 오류는 401 `UNAUTHORIZED`, 소유권 불일치는 403 `FORBIDDEN`을 제안한다. 자원 은닉을 위한 404 사용 여부는 협의한다.

### 3.2 응답 envelope 및 오류

**[코드 확인]** `ApiEnvelope<T>`의 필수 필드: `success:boolean`, `code:string`, `message:string`, `data:T`. 클라이언트는 data만 반환한다. `!res.ok`, JSON 파싱 실패, `success=false` 중 하나라도 해당하면 ApiError(code, message, HTTP status)를 발생시킨다. 필드 구조를 런타임에 검증하지는 않는다.

데모 성공 예시(envelope 구조는 코드 확인, COMMON_200/COMMON_201 및 공통 응답 컨벤션을 따르는 안):

```json
{"success":true,"code":"COMMON_200","message":"요청이 성공했습니다.","data":{"holdId":"h_001","concertId":"c1","seatIds":["A-05"],"expiresAt":"2026-11-01T04:05:00Z"}}
```

오류 예시 (code와 409는 mock 확인, envelope 적용 및 메시지는 예시):

```json
{"success":false,"code":"SEAT_ALREADY_TAKEN","message":"이미 선택된 좌석입니다: A-05","data":null}
```

**[신규 제안]** 아래에 제시한 모든 오류는 이 형식을 사용한다. 공통 400 `INVALID_REQUEST`(형식·검증 오류), 500 `INTERNAL_SERVER_ERROR`를 사용하고 공통 처리기의 코드·메시지 컨벤션을 따른다. 검증 상세는 `data.errors`가 될 수 있으므로 모든 오류 data를 null로 고정하지 않는다. BTS 장애의 데모 응답은 503 `SERVICE_UNAVAILABLE` 변환안을 사용하며 서비스 키 오류를 데모 사용자 세션 오류로 그대로 노출하지 않는다. 실제 BTS의 HTTP/code/message 매핑과 처리 정책은 [연동 설계 5절](bts-integration.md)을 따른다. endpoint별 표의 오류는 공통 오류에 추가된다.

**중요:** DELETE나 이벤트 수집도 HTTP 204/빈 본문이면 현재 클라이언트는 실패로 처리한다. `Promise<void>` 또는 응답 무시 함수도 **200 + 성공 envelope + data:null**을 반환하는 호환 정책을 제안한다. 아래 JSON은 별도 언급이 없으면 **envelope 내부 data의 예시**다. 각 endpoint는 반드시 위 envelope로 감싸 반환한다.

### 3.3 데이터 타입 계약 [코드 확인]

| 타입 | 필드와 타입 |
|---|---|
| BtsUser(현재 FE 타입) | `userId:string`; 추가 unknown 필드 허용. 현재 FE 최소 타입일 뿐 실제 BTS UserResponse와 다름 |
| Concert | `id,title,artist,venue,startAt,tagline:string`; `priceFrom,hue:number` |
| Seat | `id,row:string`; `number,price:number`; `grade:VIP\|R\|S`; `status:AVAILABLE\|HELD\|BOOKED`; `heldByMe?:boolean` |
| SeatHold | `holdId,concertId,expiresAt:string`; `seatIds:string[]` |
| Booking | `id,concertId,concertTitle,venue,startAt,createdAt:string`; `seatIds:string[]`; `totalPrice:number`; `status:CONFIRMED\|CANCELLED` |
| TicketQr | `token:string`; `expiresAt?:string`; `refreshIntervalSec?:number`; 전체 응답 data가 null일 수도 있음 |
| QueueStatus / QueueTicket | `position,behind:number`; QueueTicket에만 `ticketId:string` 추가 |

날짜는 ISO 8601 문자열이다. Mock에는 +09:00과 UTC의 Z 표기가 혼재한다. 금액은 원화로 표시한다(`won` 함수). **[신규 제안]** 데모 서버는 KRW 정수와 UTC 기준 DB 날짜를 사용한다. 이 제안은 BTS의 사용자·Assessment·Policy LocalDateTime 정책을 변경하지 않으며 BTS 이벤트만 확정된 초 단위 UTC 계약을 따른다. 좌석 ID(예: A-05)는 공연 간 중복되므로 공연과 함께 식별해야 한다.

## 4. 기존 FE API 목록과 개별 명세

이 절은 **브라우저→데모**의 URL이며 모두 `/api/v1`을 포함한다. 정상 HTTP Status는 **데모 설계 제안**이다(Mock은 HTTP 통신을 하지 않는다). BTS User/Event의 확정 status/code와 혼동하지 않는다. Mock에서 확인한 오류 code/status는 별도로 표시한다. 인증·추가 오류·멱등성 강화도 제안이다. 각 절의 JSON은 별도 표시가 없으면 공통 envelope의 **data 부분**이다. 요청·응답 필드 타입은 3.3절을 함께 적용한다.

### 4.1 데모 사용자 시작/등록 호환안: POST `/api/v1/users`

- **[코드 확인]** `api/bts.ts:registerUser`는 `{"userId":"demo_user01"}`을 전송한다. 사용 파일: `auth/AuthContext.tsx` → `pages/LoginPage.tsx`.
- **[신규 제안]** 이 요청을 받는 데모 adapter를 두되 실제 로그인/세션 계약은 별도 합의한다. 데모 인증 전 시작 요청 허용 여부와 기존 계정 재접속 검증은 미정. BTS 키를 요구하는 브라우저 API로 만들지 않는다.
- Request: path/query 없음, userId 필수 string. 현재 FE는 trim 후 3~30자 정규식 검사. 이는 데모 입력 정책이며 BTS 요청에 같은 trim/정규화를 추가하는 근거가 아니다.
- Response data 호환 예: `{"userId":"demo_user01"}`. 여기 userId는 데모 공개 식별자이며 BTS UUID와 구분한다. 세션 토큰 발급은 별도 계약.
- 데모 Status: 신규 201/COMMON_201, 재사용 200/COMMON_200 제안. 400 INVALID_REQUEST 및 공통 오류. BTS 실패는 데모 공통 예외로 변환하며 단순 재등록을 충돌 오류로 처리하지 않는다.
- 서버 내부: 고정 serviceId + 저장된 externalUserId로 **BTS POST /api/v1/users** 호출. 반환 UUID를 데모 bts_user_id에 매핑한다. BTS body는 `{"serviceId":"ticket-service-a","externalUserId":"external-user-123","accountStatus":"ACTIVE"}`이며 현재 FE body를 그대로 보내면 안 된다.
- BTS 신규/재등록의 확정 필드·제약·Credit 70/BASE·롤백 계약은 [연동 설계 3절](bts-integration.md) 참조. 재등록은 기존 상태/Credit을 변경하지 않는다.

### 4.2 데모 본인 조회 호환안: GET `/api/v1/users/{userId}`

- **[코드 확인]** `api/bts.ts:getUser` 함수는 있으나 화면 호출은 없다. userId는 encodeURIComponent 처리.
- **[신규 제안]** 데모 인증 필수/본인 제한. Request는 데모 공개 userId:string path, query/body 없음. 브라우저 입력을 BTS UUID로 신뢰하지 않는다.
- Response data 최소 호환 예: `{"userId":"demo_user01"}`. 추가 표시 필드는 데모 DTO로 별도 확정한다. 데모 Status 200/COMMON_200, 본인 자원 미존재 404/USER_NOT_FOUND, 공통 인증 오류 제안.
- 데모 본인 권한 확인 → 저장된 UUID 조회 → **BTS GET /api/v1/users/{UUID}**를 서버 키로 호출. BTS는 서비스 범위만 제한하므로 데모의 본인 검증이 반드시 필요하다.
- 실제 상세는 creditScore/tier와 null 포함 anomalyScore/lastAssessedAt/latestAction을 제공한다. Assessment와 Policy는 독립 최신 이력이며 상세 조회에만 세 필드를 추가한다. 정렬·타 서비스 404·LocalDateTime 유지 규칙은 [연동 설계 3절](bts-integration.md) 참조.
- MyPage의 baseCredit/penalties/suspicionScore/한글 grade는 이 응답에 없다. anomalyScore를 같은 의심 점수로 만들거나 BASE를 안전 등급으로 임의 변환하지 않는다.

### 4.3 데모 행동 텔레메트리 유지안: POST `/api/v1/events`

- **[코드 확인/기존 FE 가정]** `api/bts.ts:sendBehaviorEvent`, `api/types.ts:BehaviorEventPayload`, `hooks/useBehavior.ts`가 아래 9종 신호를 전송한다. **BTS 수집 API와 다른 계약**이다.
- 사용 파일: `pages/WaitingRoom.tsx`, `pages/ConcertDetailPage.tsx`, `pages/PaymentPage.tsx`, `pages/MyPage.tsx`.
- 데모 인증 필수 및 userId 일치 확인 제안. Request: path/query 없음. userId,eventType,occurredAt,deviceId 필수 string, metadata 선택 객체.
- 현재 FE 요청 예:

```json
{"userId":"demo_user01","eventType":"SELECT_SEAT","occurredAt":"2026-11-01T04:00:00.125Z","deviceId":"dev_example","metadata":{"concertId":"c1","seatId":"A-05","elapsedMs":1250}}
```

- 데모 Response 유지안: `{"success":true,"code":"COMMON_200","message":"요청이 성공했습니다.","data":null}`. 200, 검증 오류 400/INVALID_REQUEST 제안. 실제로 수집/저장할지 또는 FE 전송을 중단할지는 미정이며 구현되지 않았다.
- useBehavior는 미로그인 시 미전송, 실패 시 console.warn만 남긴다. 브라우저 발생 시각/기기 정보는 자기 신고다. metadata 내부 키를 임의 변환하지 않는다.
- raw payload를 BTS에 중계하지 않는다. BTS에 필요한 serviceId/externalEventId/UUID 및 허용 eventType/초 단위 시각이 모두 다르다.

| eventType | 전송 위치/조건 | 현재 metadata |
|---|---|---|
| JOIN_QUEUE | WaitingRoom: join 성공 | concertId, position |
| VIEW_CONCERT | ConcertDetailPage: 초기 조회 성공 | concertId |
| VIEW_SEATS | 위와 같음 | concertId |
| SELECT_SEAT | 새 좌석 선택 시 | concertId, seatId, elapsedMs |
| HOLD_SEAT | 선점 성공 | concertId, seatIds, elapsedMs |
| HOLD_FAILED | 선점 실패 | concertId, seatIds, code(불명확하면 UNKNOWN) |
| ENTER_PAYMENT | PaymentPage: 초기 조회 성공 | concertId, seatIds |
| BOOKING_CONFIRM | 예매 생성 성공 | concertId, bookingId, seatIds |
| BOOKING_CANCEL | MyPage: 확정 예매 취소 성공 | bookingId, concertId, stage="CONFIRMED" |
| BOOKING_CANCEL | PaymentPage: 이전으로 클릭, 해제 결과 확인 전 | concertId, stage="PAYMENT", seatIds |


좌석 선택 해제·5번째 선택 거부·로그인·목록/QR 조회에는 전용 이벤트가 없다. PAYMENT 단계 BOOKING_CANCEL은 확정 예매 취소나 해제 성공의 증거가 아니다. 서버의 예매 확정/실제 취소에서 각각 BTS BOOKING/CANCEL을 생성하며 브라우저 알림은 원천으로 사용하지 않는다. CHECK_IN은 아직 없는 서버 검표 성공에서만 생성한다. 선점 만료도 CANCEL로 보내지 않는다.

### 4.4 공연 목록: GET `/api/v1/concerts`

**[1단계 구현]** 아래 FE 응답 형식을 유지한다. 인증 없이 조회, 성공 HTTP 200/COMMON_200. 공연 시작 시각→공개 ID 순으로 정렬한다.

- **[FE 가정]** `api/ticketing.ts:real.listConcerts`.
- 사용 파일: `pages/ConcertListPage.tsx`.
- 인증: 우선 필수 제안(현재 UI는 로그인 후 표시). 공개 여부는 협의.
- Request: path/query/body 없음. 검색·페이지네이션 미정의.
- Response data: Concert[]. 비어 있으면 []. 예:
```json
[{"id":"c1","title":"MIDNIGHT SIGNAL — Seoul Encore","artist":"Nova Lane","venue":"올림픽공원 KSPO DOME","startAt":"2026-12-12T18:00:00+09:00","priceFrom":99000,"tagline":"밤 사이로 번지는 신호, 앙코르 서울","hue":265}]
```

- Status: 200. 코드에서 확인한 고유 오류는 없으며 공통 오류 적용.

### 4.5 공연 상세: GET `/api/v1/concerts/{concertId}`

**[1단계 구현]** 인증 없이 조회, 200/COMMON_200. 없는 공연 404/CONCERT_NOT_FOUND. concertId 공백/50자 초과는 400/INVALID_REQUEST(data.errors). DB UTC 시각을 오프셋 포함 문자열로 반환한다.

- **[FE 가정]** `api/ticketing.ts:real.getConcert`.
- 사용 파일: `pages/WaitingRoom.tsx`, `pages/ConcertDetailPage.tsx`, `pages/PaymentPage.tsx`.
- 인증: 필수 제안.
- Request: path `concertId:string`, query/body 없음.
- Response data: Concert. 예:
```json
{"id":"c1","title":"MIDNIGHT SIGNAL — Seoul Encore","artist":"Nova Lane","venue":"올림픽공원 KSPO DOME","startAt":"2026-12-12T18:00:00+09:00","priceFrom":99000,"tagline":"밤 사이로 번지는 신호, 앙코르 서울","hue":265}
```

- Status: 200 제안. **Mock 확인:** 404 `CONCERT_NOT_FOUND`.

### 4.6 좌석 목록: GET `/api/v1/concerts/{concertId}/seats`

**[1단계 구현]** 인증/대기열 검증 없이 조회, 200/COMMON_200. 공연 미존재는 상세와 같은 404. 행→좌석 번호 순 정렬, heldByMe=false. 이후 선점 단계에서 본인 판정을 추가한다. 403/QUEUE_NOT_ADMITTED는 아직 설계 제안이다.

- **[FE 가정]** `api/ticketing.ts:real.listSeats`.
- 사용 파일: `pages/ConcertDetailPage.tsx`, `pages/PaymentPage.tsx`; `components/SeatMap.tsx`는 표시만 담당.
- 인증: 필수 제안.
- Request: path `concertId:string`, query/body 없음. 함수의 userId 인자는 real 요청에 전송하지 않음.
- Response data: Seat[]. 예:
```json
[{"id":"A-05","row":"A","number":5,"grade":"VIP","price":154000,"status":"AVAILABLE","heldByMe":false},{"id":"A-06","row":"A","number":6,"grade":"VIP","price":154000,"status":"HELD","heldByMe":true}]
```

- Status: 200, 404 `CONCERT_NOT_FOUND`, 403 `QUEUE_NOT_ADMITTED` 제안. Mock은 없는 공연에도 좌석을 생성하므로 존재 검증은 신규 사항.
- heldByMe는 인증 주체에서 산출하고 타인 ID를 반환하지 않는 방안을 제안. 현재 주기적 좌석 조회는 없으며 페이지 진입과 선점 실패 시 갱신한다. 실시간 push는 미구현.

### 4.7 좌석 선점: POST `/api/v1/concerts/{concertId}/seat-holds`

- **[FE 가정]** `api/ticketing.ts:real.holdSeats`.
- 사용 파일: `pages/ConcertDetailPage.tsx`.
- 인증: 필수 및 입장 자격 확인 제안.
- Request: path `concertId:string`. Body: `{"seatIds":["A-05","A-06"]}`(필수 string[]). userId/금액 전송 없음.
- Response data: SeatHold. 예:
```json
{"holdId":"h_001","concertId":"c1","seatIds":["A-05","A-06"],"expiresAt":"2026-11-01T04:05:00Z"}
```

- Status: 201 제안. **Mock 확인:** 409 `SEAT_ALREADY_TAKEN`. 추가 제안: 400 `INVALID_SEAT_SELECTION`(빈 목록·중복·5석 이상), 404 `CONCERT_NOT_FOUND`/`SEAT_NOT_FOUND`, 403 `QUEUE_NOT_ADMITTED`.
- Mock은 5분 유효, 같은 사용자·공연의 기존 선점을 새 선점으로 교체한다. 타인 선점/예매 완료와 충돌하면 실패한다. 5분은 가정이며 4석 제한은 UI에만 있고 Mock에는 검증이 없다.
- 서버에서는 전 좌석을 하나의 트랜잭션으로 처리하고 교체 실패 시 기존 선점을 유지할 것을 제안한다. Mock 반환 객체에는 userId도 있지만 SeatHold 타입 계약은 위 4개 필드다.

### 4.8 선점 해제: DELETE `/api/v1/seat-holds/{holdId}`

- **[FE 가정·명시적 TODO]** `api/ticketing.ts:real.releaseHold`.
- 사용 파일: `pages/PaymentPage.tsx:goBack`.
- 인증: 필수/본인 제안.
- Request: path `holdId:string`, query/body 없음.
- Response data: null 제안. 완전한 예: `{"success":true,"code":"COMMON_200","message":"요청이 성공했습니다.","data":null}`.
- Status: 200 제안. Mock은 이미 해제/만료된 경우에도 성공하며 서버도 이 멱등성을 유지할 것을 제안. 타인 ID는 403 또는 자원 은닉 정책에 따라 404.
- 명시적 해제는 Mock에서 미입금 자동 취소 횟수에 가산하지 않는다.

### 4.9 예매 생성·확정: POST `/api/v1/bookings`

- **[FE 가정]** `api/ticketing.ts:real.confirmBooking`.
- 사용 파일: `pages/PaymentPage.tsx:pay`.
- 인증: 필수/선점 소유자 제안.
- Request: path/query 없음. Body: `{"holdId":"h_001"}`(필수 string).
- **[신규 변경안]** metadata 원천 수집을 위해 데모 외부 요청을 다음과 같이 확장한다. `paymentIdentifier`는 필수 테스트 식별자(공백 불가, 최대 100자, 서버 허용 목록 검증)로 정의하는 안이다. 기존 FE는 아직 전송하지 않으므로 구현 시 FE/Mock과 함께 변경해야 한다.

```json
{"holdId":"h_001","paymentIdentifier":"test-pay-shared-01"}
```

- 기존 holdId-only는 현재 코드 계약이지 새 수집 요구를 충족하는 요청이 아니다. 새 계약 적용 후 누락/잘못된 paymentIdentifier는 400/INVALID_REQUEST. 같은 hold 재확정은 저장된 예매를 반환하고 새 결제 식별자·IP·시각·BTS 이벤트로 덮어쓰지 않는다.
- ipAddress, bookingDurationSeconds, occurredAt, serviceId, externalEventId는 브라우저 입력 필드로 추가하지 않는다. 서버가 성공한 업무 전이에서 생성한다. 15자리 카드 입력값/실카드번호는 전송하지 않는다.
- Response data: Booking. 예:
```json
{"id":"b_001","concertId":"c1","concertTitle":"MIDNIGHT SIGNAL — Seoul Encore","venue":"올림픽공원 KSPO DOME","startAt":"2026-12-12T18:00:00+09:00","seatIds":["A-05","A-06"],"totalPrice":308000,"status":"CONFIRMED","createdAt":"2026-11-01T04:01:00Z"}
```

- Status: 최초 201, 동일 hold 재시도는 기존 Booking으로 200 제안. **Mock 확인:** 410 `HOLD_EXPIRED`(본인 hold를 찾지 못한 경우도 포함). 추가 제안: 403 `FORBIDDEN`, 409 `BOOKING_CONFLICT`.
- Mock은 hold 삭제 후 좌석을 확정하고 좌석 목록 가격으로 합계를 계산한다. 반환 객체에는 userId도 있지만 Booking 타입에는 없다.
- 현재 계약은 결제 승인을 포함하지 않는 데모 확정이다. PG 승인 연동은 별도 계약 변경이 필요하다. 서버 금액 계산, 만료/소유권/좌석 상태 확인, 일괄 확정, holdId 유일 제약을 통한 중복 방지를 제안.
- **[연동 결정]** 예매·좌석 확정과 같은 트랜잭션에서 BOOKING outbox를 생성한다. metadata에 서버 소요 초, 검증된 테스트 결제 식별자, 서버 관측 IP와 관람 대상 문맥을 고정한다. 실패/롤백은 이벤트를 남기지 않는다. [원천 수집 프로필](bts-integration.md) 4.5절 참조.

### 4.10 예매 취소: POST `/api/v1/bookings/{bookingId}/cancel`

- **[FE 가정]** `api/ticketing.ts:real.cancelBooking`.
- 사용 파일: `pages/MyPage.tsx:cancel`.
- 인증: 필수/본인 제안.
- Request: path `bookingId:string`, query/body 없음.
- Response data: Booking. 예:
```json
{"id":"b_001","concertId":"c1","concertTitle":"MIDNIGHT SIGNAL — Seoul Encore","venue":"올림픽공원 KSPO DOME","startAt":"2026-12-12T18:00:00+09:00","seatIds":["A-05","A-06"],"totalPrice":308000,"status":"CANCELLED","createdAt":"2026-11-01T04:01:00Z"}
```

- Status: 200 제안. **Mock 확인:** 404 `BOOKING_NOT_FOUND`. 이미 CANCELLED면 같은 결과로 성공한다. 409 `BOOKING_NOT_CANCELLABLE`(취소 기한/입장 후 등)은 추가 제안이며 제한 정책 자체도 협의 대상.
- Mock은 모든 좌석을 복구한다. 부분 취소·수수료·환불 처리는 없다. 취소 시 QR 사용도 무효화할 것을 제안.
- 성공한 CONFIRMED→CANCELLED 전이에서만 CANCEL outbox를 생성한다. 같은 bookingId/공연·티켓 snapshot을 사용하고 재취소로 새 이벤트를 만들지 않는다. 선점 해제/만료는 CANCEL로 전송하지 않는다.

### 4.11 내 예매 내역: GET `/api/v1/bookings/me`

- **[FE 가정]** `api/ticketing.ts:real.listMyBookings`.
- 사용 파일: `pages/MyPage.tsx`.
- 인증: 필수 제안.
- Request: path/query/body 없음. userId query도 없음.
- Response data: Booking[], 비어 있으면 []. 예:
```json
[{"id":"b_001","concertId":"c1","concertTitle":"MIDNIGHT SIGNAL — Seoul Encore","venue":"올림픽공원 KSPO DOME","startAt":"2026-12-12T18:00:00+09:00","seatIds":["A-05"],"totalPrice":154000,"status":"CONFIRMED","createdAt":"2026-11-01T04:01:00Z"}]
```

- Status: 200. 코드에서 확인된 고유 오류 없음. Mock은 본인의 전체 내역을 생성 역순으로 반환한다. CONFIRMED/CANCELLED 모두 포함하는 계약 유지 제안. 페이지네이션 없음.

### 4.12 예매 QR: GET `/api/v1/bookings/{bookingId}/qr`

- **[FE 가정·명시적 TODO]** `api/ticketing.ts:real.getTicketQr`, TicketQr.
- 사용 파일: `components/QrModal.tsx` ← `pages/MyPage.tsx`.
- 인증: 필수/본인 제안.
- Request: path `bookingId:string`, query/body 없음.
- Response data: TicketQr 또는 null. 예:
```json
{"token":"opaque-demo-ticket-token","expiresAt":"2026-11-01T04:01:15Z","refreshIntervalSec":15}
```

- 발급 전 완전한 예: `{"success":true,"code":"COMMON_200","message":"발급 전입니다.","data":null}`.
- Status: 200(미발급도 200/null), 404 `BOOKING_NOT_FOUND`, 409 `BOOKING_CANCELLED` 제안.
- Mock은 기본 null. VITE_MOCK_QR=true이면 `MOCK-QR:<bookingId>:<timestamp>`와 refreshIntervalSec=15를 반환하나 검증 가능한 입장권은 아니다. FE의 15초 갱신/만료 판정은 미구현. 발급 주체·검표 API·여러 좌석의 입장 단위는 협의.
- QR 조회/발급은 CHECK_IN을 생성하지 않는다. 별도 서버 검표가 성공할 때 안정적인 ticketId로 CHECK_IN을 생성하며 QR 회전 token을 ticketId로 사용하지 않는다.

### 4.13 대기열 진입: POST `/api/v1/concerts/{concertId}/queue`

- **[FE 가정]** `api/queue.ts:real.join`.
- 사용 파일: `pages/WaitingRoom.tsx`.
- 인증: 필수 제안.
- Request: path `concertId:string`, query/body 없음(userId는 real에서 미전송).
- Response data: QueueTicket. 예: `{"ticketId":"q_001","position":8,"behind":32}`.
- Status: 200 제안. 같은 사용자·공연의 재요청은 기존 ticket/순번 반환(Mock 동작). 404 `CONCERT_NOT_FOUND`, 409 `QUEUE_CLOSED` 추가 제안.
- position은 내 앞의 인원 수이며 0이면 입장 가능. behind는 내 뒤 인원 수.

### 4.14 대기열 상태: GET `/api/v1/queue/{ticketId}`

- **[FE 가정]** `api/queue.ts:real.status`.
- 사용 파일: `pages/WaitingRoom.tsx`의 250ms 폴링.
- 인증: 필수/본인 제안.
- Request: path `ticketId:string`, query/body 없음.
- Response data: QueueStatus. 예: `{"position":3,"behind":40}`.
- Status: 200, 404 `QUEUE_TICKET_NOT_FOUND`, 410 `QUEUE_TICKET_EXPIRED` 제안.
- Mock은 알 수 없는 ticket에 0/0을 반환하지만 실서버는 이를 입장 가능으로 처리하지 않는다. FE는 status 오류를 무시하고 재조회하므로 만료 시 재진입 UI가 필요하다.

### 4.15 대기열 이탈: DELETE `/api/v1/queue/{ticketId}`

- **[FE 가정]** `api/queue.ts:real.leave`.
- 사용 파일: `pages/WaitingRoom.tsx`의 입장 전 unmount cleanup.
- 인증: 필수/본인 제안.
- Request: path `ticketId:string`, query/body 없음.
- Response data: null 제안. 완전한 예: `{"success":true,"code":"COMMON_200","message":"요청이 성공했습니다.","data":null}`.
- Status: 200 제안. 이미 이탈/만료되어도 성공, 존재하는 타인 ticket에는 403 제안. 입장 성공 시 현재 leave를 호출하지 않으므로 서버의 대기→입장 전환/정리가 필요하다.

## 5. Mock·브라우저 내부 처리 범위

| 항목 | 현재 상태와 한계 |
|---|---|
| 설정 | TICKETING/BTS/QUEUE 스위치는 문자열 false일 때만 real, 나머지는 Mock. .env.example은 모두 true. QR 스위치는 true일 때만 Mock QR 발급 |
| 공연 | c1/c2/c3 3건 하드코딩. 제목·장소·일시·hue 등 포함 |
| 좌석 | 공연마다 A~H 8행×12석=96석. A/B는 VIP 154,000원, C~E는 R 132,000원, F~H는 S 99,000원. 공연 ID seed 기반 약 28%를 예매 완료 상태로 생성 |
| 가격 표시 | priceFrom과 생성 좌석 최저가 불일치. c2=88,000원, c3=110,000원인데 모든 공연 S석은 99,000원. 서버 데이터 정합성 필요 |
| 저장 | fds.mock.ticketing localStorage에 booked/holds/bookings/expired 저장. 브라우저 간 공유 없음. 동시성 제어·트랜잭션 없음 |
| 선점 만료 | 5분. load 후 purge를 호출하는 경로에서 지연 정리하며 백그라운드 작업은 없음. 만료 hold마다 사용자 expired 횟수 증가 |
| 로그인 | fds.userId 저장. BTS Mock은 {userId} 반환뿐이며 사용자 DB/본인 검증 없음 |
| Token | fds.token을 request에서 읽기만 함. 발급/갱신/삭제의 화면 연동 없음 |
| 대기열 | 모듈 Map. 초기 4~9명(설정 가능), 1초마다 앞 인원 1 감소, 2초마다 뒤 인원 1 증가. 실제 사용자/서버 부하와 무관. reload 시 Map 소멸 |
| 입장 기록 | fds.admit.<userId>.<concertId> sessionStorage에 15분 만료 저장. Gate 초기화 시 확인하며 화면 유지 중 만료 감시는 없음. clearAdmitted는 미사용 |
| 결제 | 가상 15자리 번호는 component state에만 존재. 저장/전송 없음. 1.2초 sleep 후 bookings 호출. PG·결제 ID·환불 API 없음 |
| QR | null 또는 가짜 token. qrcode.react로 그림 생성. 검표·재사용 방지 없음 |
| 행동 | fds.deviceId에 무작위 UUID 저장, 실패 시 dev_unknown. 브라우저 제공 값이며 신뢰할 수 있는 기기 인증은 아님 |
| 신용 계산 | getAccountRisk는 HTTP 호출 없이 항상 로컬 계산. BTS Mock=false여도 동일. 실제 ticketing 사용 시 autoCancel=0 |
| 신호 | fds.signals.<userId>에 최근 10개 새 좌석 선택 시각과 최근 첫 선택 소요 초 저장. 5개 시각 이상이면 인접 간격의 모분산 계산. 모든 조작을 기록하는 것은 아님 |

신용 계산 구현: `BaseCredit = 100*(attended+5)/(attended+2*noShow+1.5*autoCancel+10)`, 신용=max(0, BaseCredit−감점 합계). 감점은 anomaly×40, 동일 카드 4계정 이상 15점/2계정 이상 5점, 동일 IP 10분 내 5회 이상 시도 5점, 첫 선택 1초 미만 5점, 선택 간격 분산 1초² 미만 5점이다. 등급은 70 이상 우수, 40 이상 안전, 20 이상 주의, 그 미만 위험. 결과는 소수 첫째 자리로 반올림한다.

현재 attended/noShow/anomaly/cardAccounts/ipAttempts10m은 모두 0 고정이다. 가중치·등급 경계·의심 점수=감점 합계 정의는 주석에서도 후보/임시값이므로 BTS 확정 정책으로 간주하지 않는다. 새 사용자·신호 없음이면 기본 50, 신용 50, 의심 0, 안전이다.

이 절은 **현재 Mock/FE 동작 분석**이다. 확정된 실연동에서는 데모의 로컬 Feature/신용 계산을 사용하지 않고 원천을 BTS로 전달하며 BTS 조회값을 표시한다. Mock 전용 계산과 실연동 경로를 분리해야 한다.

## 6. 새 계약이 필요한 기능(기존 API와 구분)

아래는 구현된 FE endpoint가 아니다. 합의하지 않은 URL/body를 추측하여 확정 API 목록에 추가하지 않는다.

| 기능 | 코드에서 확인한 공백 | 제안/협의 사항 |
|---|---|---|
| 데모 인증 | 사용자 등록만 있고 token 발급 없음 | 사이트 독립 세션 발급/종료·본인 조회 계약. 경로/body/응답/token 만료 미정. ID만 쓰는 공유 데모인지 실제 본인 인증이 필요한지 먼저 합의 |
| 대기열 입장 자격 | 브라우저 기록으로 통과 | queue 응답에 입장 token 추가 또는 서버에서 사용자·공연별 자격 저장. FE 변경을 줄이는 초기안은 후자. 자격 만료와 보호 endpoint 범위 합의 |
| 신용/평가 조회 | MyPage는 로컬 계산 | BTS GET /users/{UUID}의 creditScore/tier/anomalyScore/lastAssessedAt/latestAction 사용. 주석의 /credit·/assessments/latest를 실제 API로 가정하지 않음. FE 감점 상세와 호환되지 않으므로 표시 DTO/UI 변경 필요 |
| PG 결제 | 현재 bookings에는 holdId만 전송 | 테스트 결제 식별자 확장은 아래 별도 항목. 실제 PG 준비/승인/통지/환불·상태 전이는 별도 설계하며 생카드번호를 추가하지 않음 |
| 테스트 결제 원천 | 15자리 값은 state에만 있고 전송 없음 | 기존 holdId 요청에 paymentIdentifier를 추가하는 데모 계약 변경안. 재사용 가능한 테스트 허용 목록 사용. 실제 PG는 별도 범위 |
| QR 검표/관람·노쇼 | QR 표시만 구현 | 발급·검증 담당, 만료/일회성 소비/여러 좌석 입장 단위/관람·노쇼 확정 계약 합의. 검표 endpoint는 현재 FE에 없음 |
| 선점 복원/화면 재개 | router state 의존 | 필요 시 본인의 유효 hold 조회 추가. URL 미정이며 이번 필수 API로 가정하지 않음 |

## 7. 백엔드 설계안 [신규 제안]

전제: Spring Boot, Java 17, Gradle, MySQL. 구체적인 Spring Boot/Gradle 버전은 구현 착수 시 Java 17 호환 조합으로 선정한다. Web, Validation, Data JPA, Security, MySQL driver를 후보로 삼되 기존 BTS의 레이어·DTO·common 응답/예외·SQL 관리 컨벤션을 유지한다. SQL 경로/실행 규칙은 실제 소스 확인 후 따르며 새 migration 도구 도입을 확정하지 않는다. 이번에는 의존성 도입이나 코드 생성을 하지 않는다.

### 7.1 패키지와 책임

root package는 조직 도메인 확정까지 `<base-package>`로 표시한다. API DTO는 Entity와 분리하고 기존 FE의 문자열 ID와 필드명을 유지한다.
```text
backend/
  src/main/java/<base-package>/
    common/          # 기존 ApiResponse, ApiException, GlobalExceptionHandler 컨벤션
    config/          # Security, CORS, 영속성 설정
    auth/            # 데모 세션·인증 주체
    user/            # 데모 사용자와 BTS 사용자 매핑
    concert/         # controller, service, repository, entity, dto
    seat/            # 좌석 조회·선점·만료
    booking/         # 확정·취소·본인 내역
    queue/           # 대기열·입장 자격, 저장소 인터페이스
    ticket/          # QR 발급/검증 adapter
    integration/bts/ # BTS client, 계약 DTO, 재시도·전송
    outbox/          # BTS 허용 업무 이벤트의 불변 payload·재시도
  src/main/resources/
    application.yml
    <기존 SQL 관리 경로>/
  src/test/java/<base-package>/
```

| Controller | Service | Repository/외부 adapter |
|---|---|---|
| AuthController(계약 미정) | AuthService | DemoUserRepository, DemoSessionRepository 등 |
| UserController(/users 호환 채택 시) | UserService | DemoUserRepository, BtsClient |
| ConcertController | ConcertService | ConcertRepository |
| SeatController / SeatHoldController | SeatQueryService, SeatHoldService, HoldExpiryService | ConcertSeatRepository, SeatHoldRepository |
| BookingController | BookingService | BookingRepository, BookingSeatRepository, 좌석/hold repositories |
| QueueController | QueueService, AdmissionService | QueueStore(MySQL 초기안, Redis 선택) |
| TicketQrController | TicketQrService | TicketCredentialRepository 또는 외부 발급 adapter |
| BehaviorEventController(텔레메트리 유지 시) | DemoTelemetryService | 데모 전용 수집 저장소. BTS raw 중계 금지 |
| 외부 Controller 없음(서버 전송기) | BtsUserSyncService, BtsEventDeliveryService | BtsClient, OutboxRepository |

Controller는 입력 검증·인증 주체 조회·DTO 변환, Service는 업무 제약/트랜잭션, Repository는 영속화를 담당한다. 좌석 DB 잠금을 잡은 채 외부 BTS 응답을 오래 기다리지 않는다.

### 7.2 Entity / 테이블

모두 설계 제안이다. FE DTO와 DB 컬럼이 1:1일 필요는 없으며 내부 ID와 공개 문자열 ID를 분리할 수 있다. 관계도와 키·선택 테이블의 상세 제약은 [ERD 설계](erd.md)를 함께 참조한다.

| Entity / table | 주요 컬럼·제약 | 목적 |
|---|---|---|
| DemoUser / demo_users | id PK, public_user_id UNIQUE, bts_service_id, bts_external_user_id, bts_user_id nullable UNIQUE, created_at | UNIQUE(bts_service_id,bts_external_user_id). 외부 ID 고정·반환 UUID 매핑. BTS DB 공유 없음 |
| DemoSession / demo_sessions(인증 방식에 따라 선택) | id PK, user_id FK, token_hash UNIQUE, expires_at, revoked_at | opaque token 방식 세션. JWT 등 선택 시 재검토 |
| Concert / concerts | id PK, public_id UNIQUE, title, artist, venue, start_at, end_at, price_from, tagline, hue | end_at은 관람 대상 판정 원천을 위한 신규 설계. 종료>시작. FE에는 현재 없음 |
| ConcertSeat / concert_seats | id PK, concert_id FK, seat_code, row_label, seat_number, grade, price, state, active_hold_id nullable FK, active_booking_id nullable FK, version | UNIQUE(concert_id, seat_code). 현재 점유의 기준 |
| SeatHold / seat_holds | id PK, public_id UNIQUE, user_id FK, concert_id FK, status, expires_at, created_at | ACTIVE/RELEASED/EXPIRED/CONVERTED. 내부 상태이며 FE 타입 확장 불필요 |
| SeatHoldItem / seat_hold_items | hold_id FK, concert_seat_id FK, price_snapshot | PK(hold_id, concert_seat_id). 과거 선점 보존을 위해 seat 단독 UNIQUE 금지 |
| Booking / bookings | id PK, public_id UNIQUE, user_id FK, concert_id FK, hold_id UNIQUE FK, status, total_price, concert_title_snapshot, venue_snapshot, start_at_snapshot, end_at_snapshot, booking_started_at, confirmed_at, booking_duration_seconds, payment_identifier, ip_address, created_at, cancelled_at | 서버 소요 시간/테스트 결제/IP·일정 snapshot 보존. 같은 hold 재시도는 원본 유지 |
| BookingSeat / booking_seats | booking_id FK, concert_seat_id FK, seat_code_snapshot, grade_snapshot, price_snapshot | PK(booking_id, concert_seat_id). 취소 후 재판매 이력을 위해 seat 단독 UNIQUE 금지 |
| AdmissionTicket / admission_tickets | id PK, public_id UNIQUE, booking_id, concert_seat_id, status, checked_in_at nullable | UNIQUE(booking_id,concert_seat_id), BookingSeat 복합 FK. 좌석별 ticketId와 입장 원장 신규 설계. 상세는 ERD |
| QueueEntry / queue_entries | id PK, ticket_id UNIQUE, user_id FK, concert_id FK, sequence, state, joined_at, expires_at | MySQL안. 사용자·공연별 유효 entry 중복은 트랜잭션으로 방지 |
| Admission / admissions | user_id PK/FK, concert_id PK/FK, expires_at | 복합 PK(user_id, concert_id). 서버 측 자격 저장 방식 선택 시 |
| TicketCredential / ticket_credentials(발급 담당에 따라 선택) | id PK, ticket_id FK, token_hash UNIQUE, expires_at, consumed_at, revoked_at | 티켓별 회전 가능한 QR. 안정적인 ticketId와 token 분리 |
| OutboxEvent / outbox_events(연동안) | id PK, service_id, external_event_id, business_transition_id, aggregate_id, event_type, occurred_at_utc, payload JSON, bts_event_id nullable BIGINT, delivery_status, created_at, sent_at, retries | UNIQUE(service_id,external_event_id), UNIQUE(business_transition_id,event_type). 최종 payload/ID/발생 시각을 고정해 재전송 |

인덱스 후보: bookings(user_id, created_at), seat_holds(status, expires_at), concert_seats(concert_id, state), queue_entries(concert_id, state, sequence). FK 인덱스도 확인한다. 금액은 KRW 정수(예: BIGINT), 날짜는 UTC 기준. row 대신 row_label을 저장하고 DTO에서 row로 변환한다.

만료 선점 수는 EXPIRED 전이에서 한 번만 데모 내부 이력을 생성하여 집계한다. 이를 BTS CANCEL로 전송하지 않는다. 만료 작업 재실행으로 중복 가산하지 않는다. 노쇼/관람은 별도 검표·종료 판정으로 확정하며 취소나 선점 실패로 대체하지 않는다. 분석용 feature/model/신용 이력 테이블은 기본적으로 BTS 책임이다.

### 7.3 좌석 경쟁·만료·멱등성

1. 같은 사용자·공연의 선점/교체를 직렬화하고 기존+신규 좌석을 일정 순서로 잠근다. 최초 hold가 없을 때도 유효한 사용자·공연 제어 행 등 잠금 방법을 정한다.
2. MySQL 트랜잭션에서 공연 소속, 1~4석, 중복 없음, 만료, 타인 점유를 검증한다. 모든 좌석 확보 성공 시에만 기존 선점 해제와 신규 선점을 commit한다.
3. 조회/신규 선점에서도 expires_at을 판정해 정리 작업 지연으로 좌석이 계속 막히지 않게 한다. 정리는 ACTIVE→EXPIRED 조건부 전이를 사용한다.
4. 확정은 hold/좌석 잠금 후 만료·소유권을 확인한다. Booking 생성·좌석 BOOKED·hold CONVERTED를 일괄 commit하며 hold_id UNIQUE로 재시도를 기존 Booking에 수렴시킨다.
5. 취소는 본인 확인 후 Booking 상태와 **해당 Booking이 현재 소유한 좌석만** 해제한다. 재취소로 다른 사람에게 재판매된 좌석을 해제하지 않는다. QR도 무효화한다.

DB 행 잠금과 제약을 경쟁 방지 근거로 삼는다. JVM 내부 synchronized나 localStorage만으로 처리하지 않는다. BTS 전송 실패로 commit된 Booking을 취소하지 않는다. 선점 시 저장한 가격 snapshot을 확정 금액 기준으로 사용하는 안을 제안하며, 유효 hold 도중 가격 변경 허용 여부는 협의한다.

### 7.4 대기열 기반

FE 주석은 Redis를 가정하지만 필수 기술 스택은 MySQL까지다. 작은 초기 데모는 MySQL의 공연별 순번·유효 entry·입장 자격으로 구현하는 안을 제안한다. Redis 추가 시 sorted set/TTL/원자적 진입·승격을 담당하는 QueueStore를 별도로 둔다.

join은 사용자·공연별 멱등성을 보장하고 이탈 미통지 entry는 TTL로 정리한다. 250ms는 현재 코드의 값이며 권장 부하 설정이 아니다. 실제 연동 시 간격 확대·중복 요청 억제·backoff를 검토하고 SSE/WebSocket은 추가 요구사항으로 둔다. 입장 인원·진행 속도·자격 만료·재입장 정책은 미정이다.

### 7.5 원천 데이터 수집과 구현 경계

BOOKING은 선점 created_at부터 confirmed_at까지 서버 소요 초, 확정 요청에서 관측한 IP, 재사용 가능한 테스트 paymentIdentifier를 metadata로 전송한다. CANCEL은 원 예매와 연결하고 CHECK_IN은 실제 검표 성공만 기록한다. 관람 대상용 공연 시작/종료 시각과 좌석별 ticketIds를 BOOKING/CANCEL에 제공하는 설계안이다. [연동 설계 4.5~4.6절](bts-integration.md)의 타입·신뢰 경계·BTS 별도 추출 작업을 함께 적용한다.

| Feature | 전달 원천 |
|---|---|
| booking_count | BOOKING 기록 |
| cancel_count / cancel_rate | BOOKING/CANCEL 및 bookingId |
| avg_booking_duration | BOOKING metadata.bookingDurationSeconds |
| avg_booking_interval | BOOKING occurredAt |
| card_reuse_count | BOOKING metadata.paymentIdentifier |
| ip_booking_density | BOOKING metadata.ipAddress |
| attendance_rate | CHECK_IN + 공연 종료/취소 여부를 구분할 관람 대상 예매·티켓 문맥 |

데모에서는 위 Feature의 횟수/평균/비율/밀도를 계산하지 않는다. API Key·BTS UUID·eventType·UTC 초 단위·externalEventId 정책은 그대로 유지하며 outbox에서 같은 payload를 재전송한다. 신뢰 프록시 설정이 없으면 전달 헤더를 무시하고 peer IP를 사용한다. raw 카드번호나 브라우저 신고 IP로 원천을 채우지 않는다. 실제 BTS DB와 데모 DB를 직접 연결하지 않는다.

## 8. BTS 책임 구분과 연동안

실제 계약은 [BTS 연동 설계](bts-integration.md)에 정리한다. BTS 사용자·이벤트 API를 새로 구현하거나 기존 DTO/common/SQL을 교체하는 범위가 아니다.

| 데모 책임 | BTS 확정 계약/범위 |
|---|---|
| 사용자 인증, 고정 externalUserId와 UUID 매핑 | 서비스 키 인증, serviceId/externalUserId 등록 및 서비스 범위 조회 |
| 공연·좌석·선점·예매·취소 및 실제 검표 | BOOKING/CANCEL/CHECK_IN 저장, 멱등성 검증 |
| transaction 기반 outbox, 같은 ID/payload 재시도 | (serviceId,externalEventId) 동일 내용 200, 다른 내용 409 |
| 성공 업무의 원천 metadata 생산·텔레메트리 분리 | 세 이벤트 저장 + metadata를 읽는 Feature 추출 로직의 별도 연동 작업 |
| 평가 표시 DTO와 정책 실행 여부 결정 | 상세 조회의 Credit/최신 Assessment/독립 최신 Policy. 이벤트 저장 자체는 이 데이터를 변경하지 않음 |

브라우저→데모→BTS 경계를 사용한다. X-API-Key는 데모 서버에서만 주입하고 serviceId도 서버 설정에서 결정한다. 데모 외부 /events를 BTS로 raw 중계하지 않는다. BOOKING/CANCEL은 성공한 서버 업무 전이에서 생성하고 CHECK_IN은 실제 검표가 추가된 후 생성한다.

BTS 전송 payload는 UUID, externalEventId, 초 단위 UTC 시각, metadata를 포함해 저장 후 고정한다. BTS 200/201 모두 성공이며 timeout도 같은 payload로 재전송한다. 409는 새 ID로 회피하지 않는다. 키 오류는 서비스 설정 장애이며 데모 사용자 재로그인 사유가 아니다.

BTS Entity/Repository/DB를 복제하지 않고 BtsClient 전용 DTO를 둔다. 데모의 모든 업무가 분석 기능까지 이미 지원된다고 해석하지 않는다. 기존 BTS Controller/Service/Repository/DTO/common 응답·예외/SQL 관리 규칙 유지와 실제 소스에서 확인할 항목은 연동 설계 6절을 따른다.

## 9. 구현 우선순위와 검토 항목

| 순위 | 작업 | 완료 확인 기준 |
|---|---|---|
| P0 | 데모 인증·BTS 키 설정·ID 매핑·common/SQL 컨벤션 확인 | 본인 식별과 타인 접근 거부, 실제 BTS 계약 적용, 장애 정책 결정 |
| P1 | schema/migration·데모 seed·공연/좌석 조회 | FE Mock 전환 후 기존 배열/필드로 표시, priceFrom 정합 |
| P2 | 선점/교체/해제/만료 | 같은 좌석 동시 요청 중 하나만 성공, 다중 좌석 부분 성공 없음, 만료/명시 해제 구분 |
| P3 | 데모 예매 확정/내역/취소 및 원천/outbox 원자 저장 | duration·테스트 식별자·서버 IP·티켓/일정 snapshot, 중복 확정/재취소 방지, 고정 ID/payload |
| P4 | 실제 대기열·서버 입장 자격 | 중복 join 같은 순번, 미확인 ticket 입장 불가, 직접 API 우회 불가. 본격 데모 공개 전 활성화 |
| P5 | BTS 전송·Feature 추출 연동·검표/QR | 재시도 원본 유지, CHECK_IN 원자 기록, 7개 Feature의 metadata 추출/누락·분모 검증. 동적 QR이면 FE 갱신 |
| 별도 범위 | 실제 PG/환불·일반 관리 기능 | 테스트 결제와 구분하고 별도 계약 후 진행 |

구현 단계의 테스트는 경쟁 트랜잭션·소유권·재시도·만료·JSON 계약을 우선한다. 이번 변경은 문서뿐이므로 애플리케이션 빌드/테스트는 실행하지 않고 문서와 코드의 일치 여부를 검토한다.

### 협의할 사항

1. **인증:** 공유 데모 ID인지 실제 본인 인증인지, 등록/로그인 분리, token 발급/만료/종료, 로그아웃 시 token 삭제, BTS 주체 전달.
2. **BTS 연동 구현:** 사용자·이벤트·키 인증·평가 조회 계약은 확보됨. 데모 externalUserId 생성 규칙, 실제 serviceId/비밀 전달, 키 회전, 매핑 장애 복구, outbox 재시도 한도/운영 절차를 결정.
3. **업무 제약:** 4석이 1요청/1공연/1인 누적 중 무엇인지, 선점 5분과 교체 시 만료 갱신, 취소 기한/환불, 확정 재시도, 선점 중 가격 변경.
4. **대기열:** 담당 서비스·Redis 도입 여부·입장 인원/만료·좌석 조회 보호 여부·폴링 간격·멀티탭/재진입.
5. **QR:** 발급/검표 담당·여러 좌석 입장 단위·발급 시점·동적 갱신·재사용/취소 무효화·관람/노쇼 확정.
6. **분석:** 세 이벤트+metadata 수집은 확정. BTS 추출기의 집계 기간/분모/누락·유예 정책, 공연 일정 변경, 카드·IP 원천 정규화, 점수 표시를 보완. Event 저장 자체는 분석/정책 생성이 아님.
7. **데이터/운영:** seed/가격 정합·시간대·로그/이벤트 보존·동일 origin 경로·rate limit·공개 범위.
8. **FE 보완:** token 연동/종료·대기열 만료 복귀·동적 QR·필요 시 hold 재개. 개발 시 React.StrictMode의 effect 재실행에 따른 join/cleanup 경쟁·이벤트 중복도 확인.

### 이후 BE/FE 변경 목록

[BTS 연동 설계 8절](bts-integration.md)에 담당 영역별 작업과 파일별 수정 항목을 정리했다. BE는 원천 수집·검표/티켓·불변 outbox·전송기를 구현하고 BTS는 별도 레포에서 metadata 추출을 연결한다. FE는 PaymentPage의 테스트 식별자 선택, ticketing.confirmBooking 입력 확장/Mock 동기화, 9종 telemetry 분리, MyPage의 서버 조회 표시와 로컬 계산 제거가 필요하다. 실제 구현은 아직 하지 않았다.

## 10. 작업 범위 및 중단 후 보완

성과물은 본 문서, [ERD 설계](erd.md), [BTS 연동 설계](bts-integration.md)다. 기존 화면·티켓팅 API 분석을 유지하며 확보한 실제 BTS 계약에 맞춰 데모 외부 계약과 서버 간 계약을 분리했다. 사용자 매핑·업무 이벤트·멱등성·시간·코드·컨벤션을 보완하고, 없던 bts-integration.md를 추가했다. 실제 BTS 소스/SQL 파일을 확인한 결과와 제공 명세를 혼동하지 않는다.

프론트엔드/백엔드 구현, 의존성, DB, BTS 외부 서비스는 변경하지 않았다. 기존 frontend 이동 관련 staged 변경도 유지한다.
