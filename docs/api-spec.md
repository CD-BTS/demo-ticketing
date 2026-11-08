# Demo Ticketing API 명세

## 인증과 응답

브라우저는 Demo 백엔드의 `/api/v1`을 호출한다. BTS API Key는 서버 간 인증에만 사용한다. Demo 사용자 공개 ID와 BTS 사용자 UUID는 서로 다른 식별자다.

`POST /api/v1/users`는 `{ "userId": "demo-user" }`를 받아 `{userId,token,expiresAt}`을 반환한다. userId는 영문·숫자·밑줄·하이픈 3~30자다. 새로운 ID는 등록하고 기존 ID에는 새 세션을 발급한다. 세션은 24시간 유효하며 보호된 API에는 `Authorization: Bearer <token>`을 전달한다. DB에는 원문 토큰 대신 SHA-256 hash를 저장한다. ID 입력은 시연용 식별 방식이며 실제 사용자 소유권 인증을 대신하지 않는다.

응답 envelope는 `{success,code,data,message}`다. 성공은 HTTP 200/COMMON_200 또는 201/COMMON_201이며 실패의 data는 null이다. 입력 오류 400, 미인증 401, 권한 오류 403, 없는 리소스 404, 상태 충돌 409, 만료 hold 410, 의존 서비스 장애 503을 사용한다.

## 공연과 좌석 조회

| API | 인증 | data |
|---|---|---|
| GET /api/v1/concerts | 불필요 | Concert[] |
| GET /api/v1/concerts/{concertId} | 불필요 | Concert |
| GET /api/v1/concerts/{concertId}/seats | 선택 | Seat[] |

- Concert: `id,title,artist,venue,startAt,priceFrom,tagline,hue`.
- Seat: `id,row,number,grade,price,status,heldByMe`. id는 공연 내 seat_code다. 상태는 AVAILABLE/HELD/BOOKED다.
- `heldByMe`는 유효한 인증 주체와 hold 소유자를 서버에서 비교한다. 만료된 HELD는 정리 작업 지연 중에도 AVAILABLE로 조회된다.
- 공연 ID가 없으면 CONCERT_NOT_FOUND이며 좌석을 임의 생성하지 않는다.

## 대기열과 CAPTCHA

대기열·CAPTCHA API는 Bearer 세션을 요구한다. 선택적 `X-Booking-Attempt`는 본인·공연에 연결된 attempt UUID다.

| API | 요청 | 응답 |
|---|---|---|
| POST /api/v1/concerts/{concertId}/queue | body 없음, 선택 X-Captcha-Token | 생성 201, 기존 등록 200; QueueSnapshot |
| GET /api/v1/concerts/{concertId}/queue/me | body 없음 | 200; QueueSnapshot |
| DELETE /api/v1/concerts/{concertId}/queue/me | body 없음 | 200; data=null |
| POST /api/v1/concerts/{concertId}/queue/captcha-challenges | body 없음 | 200; challengeId,question,expiresAt |
| POST /api/v1/concerts/{concertId}/queue/captcha-verifications | challengeId,answer; 선택 X-Attempt-Transition | 200; captchaToken,expiresAt |

QueueSnapshot은 `{status,position,expiresAt}`다. status는 NOT_JOINED/WAITING/ADMITTED/EXPIRED이며 WAITING 순번은 1부터, ADMITTED는 0이다. 날짜는 ISO 8601이다. 실제 API는 ticketId나 뒤 인원 수를 반환하지 않는다. 프론트엔드 adapter는 concertId를 ticketId로 사용하고 behind=0으로 채운다.

정책은 서버가 인증된 사용자 기준으로 결정한다. ALLOW와 REQUIRE_CAPTCHA는 일반 그룹, DELAY는 후순위 그룹이며 REQUIRE_CAPTCHA/DELAY는 CAPTCHA 증명이 필요하다. RESTRICT는 403/QUEUE_RESTRICTED다. 일반 그룹이 우선이고 그룹 안에서는 FIFO다. 빈 자리가 있으면 즉시 입장하며 엄격한 우선순위로 후순위가 대기 만료될 수 있다.

Redis TIME을 기준으로 만료·승격·정원을 처리한다. 동일 사용자·공연의 활성 등록은 정책 재평가나 새 순번 발급 없이 재사용한다. 이탈·만료 후 재등록에는 정책을 다시 적용한다. 브라우저 캐시를 입장 권한으로 사용하지 않는다.

CAPTCHA challenge는 기본 2분, 답 제출 최대 5회이며 grant도 기본 2분이다. 잘못된 답은 403/CAPTCHA_INVALID다. 인증 증명이 없으면 CAPTCHA_REQUIRED, 만료·소비·다른 사용자/공연/action의 grant는 CAPTCHA_INVALID다. grant 검증·일회용 소비·대기열 삽입은 Lua에서 원자적으로 수행한다. 인증 전에는 대기열 등록 멤버·순번·입장 권한을 만들지 않는다.

같은 X-Attempt-Transition의 동일 challenge/입력 재전송은 grant TTL 동안 원래 성공·실패 결과를 반환하며 실패 횟수를 다시 차감하지 않는다. 같은 ID의 다른 입력은 409/DATA_CONFLICT다. CAPTCHA 답 원문은 재전송 식별용 브라우저 저장소나 이벤트 metadata에 보관하지 않는다. 원래 grant가 만료되거나 소비된 뒤 재전송해도 grant 유효 기간을 연장하지 않는다.

POLICY_DECISION_MODE는 disabled(ALLOW), mock(서버 설정), bts(원격 정책 호출)다. bts 모드는 BTS UUID를 매핑한 뒤 `POST /api/v1/users/{uuid}/policy-decisions`에 `{context:"QUEUE_JOIN"}`을 전송하고 data.action을 읽는다. 원격 endpoint의 제공은 BTS 배포에 달려 있다. 장애·알 수 없는 action을 ALLOW로 우회하지 않는다. CAPTCHA_MODE는 disabled/mock만 지원하며 mock 산술 문제는 실제 봇 방지 공급자가 아니다.

## 좌석 선점과 예매

아래 API는 모두 Bearer 세션을 요구한다. 요청의 userId나 브라우저 입장 캐시는 권한 근거가 아니다.

| API | 요청 | 성공 |
|---|---|---|
| POST /api/v1/concerts/{concertId}/seat-holds | seatIds, 선택 attemptId/transitionId | 201; HoldResponse |
| GET /api/v1/seat-holds/{holdId} | 없음 | 200; HoldResponse |
| DELETE /api/v1/seat-holds/{holdId} | 없음 | 200; data=null |
| POST /api/v1/bookings | holdId,paymentIdentifier | 신규 201, 재확정 200; BookingResponse |
| POST /api/v1/bookings/{bookingId}/cancel | 없음 | 200; BookingResponse |
| GET /api/v1/bookings/me | 없음 | 200; BookingResponse[] |

HoldResponse는 `{holdId,concertId,seatIds,expiresAt}`다. BookingResponse는 `{id,concertId,concertTitle,venue,startAt,seatIds,totalPrice,status,createdAt}`다. 가격·공연 정보는 예매 당시 snapshot을 사용한다.

좌석은 중복 없이 1~4개 선택한다. 선점 생성·교체·재요청에는 해당 공연의 현재 Redis ADMITTED가 필요하다. 미입장은 QUEUE_NOT_ADMITTED, Redis 장애는 SERVICE_UNAVAILABLE이며 우회하지 않는다. 선점 만료 기본값은 5분이다. 유효한 본인 hold로 예매를 확정하며 선점 후 입장 TTL 만료·대기열 이탈·Redis 장애가 hold를 무효화하지 않는다.

사용자·공연별 guard와 정렬된 좌석 행 잠금으로 경쟁 요청을 직렬화한다. hold_id UNIQUE로 같은 선점에서 중복 예매를 방지한다. 예매·좌석·티켓·BOOKING Outbox는 같은 트랜잭션으로 commit한다. 취소는 CONFIRMED→CANCELLED 때만 CANCEL을 생성하며 재취소가 재판매 좌석을 풀지 않는다. 해제·만료는 CANCEL 이벤트가 아니다. 만료 시각은 expires_at <= 서버 현재 시각이며 주기 작업과 예약 경로에서 정리한다.

paymentIdentifier는 TEST_PAYMENT_IDENTIFIERS의 서버 허용 목록 값이며 실제 카드번호나 hash가 아니다. 서버 관측 peer IP를 사용하고 신뢰 프록시 설정 없이 Forwarded/X-Forwarded-For를 신뢰하지 않는다.

## 예매 시도

| API | 인증 | 요청 / 응답 |
|---|---|---|
| GET /api/v1/booking-attempts/capabilities | 불필요 | 200; {enabled:boolean} |
| PUT /api/v1/concerts/{concertId}/booking-attempts/{attemptId} | 필수 | 선택 clientOccurredAt; 200; AttemptSnapshot |
| GET /api/v1/booking-attempts/{attemptId} | 필수 | 200; AttemptSnapshot |
| POST /api/v1/booking-attempts/{attemptId}/transitions | 필수 | transitionId,stage,status; 200; AttemptSnapshot |

AttemptSnapshot은 `{attemptId,concertId,stage,status,sequence,holdId,bookingId}`다. BOOKING_ATTEMPTS_ENABLED=false이면 capability만 false를 반환하고 attempt API는 503이며 기존 attempt 없는 예매는 유지한다. 상세 전이와 멱등성은 [예매 시도 명세](booking-attempts.md)를 따른다.

## 프론트엔드 연동 경계

실제 Demo 로그인과 예매에는 VITE_USE_MOCK_BTS=false와 VITE_USE_MOCK_TICKETING=false를 설정한다. 실제 예매 설정에서는 대기열도 실제 API를 사용한다. 단계 관측은 sessionStorage에 attempt/transition 식별자와 미확정 전이를 먼저 저장한 뒤 재전송한다. 화면 새로고침은 서버의 attempt·hold·booking 상태로 복원한다.

카드 관리·QR·사용자 신용 화면에는 Mock 또는 미구현 API adapter가 남아 있다. Demo에는 카드 등록·BTS 사용자 상세 프록시·QR 발급·서버 검표 API가 없다. QR 표시나 브라우저 텔레메트리를 CHECK_IN으로 보내지 않는다. BTS UUID/Key를 브라우저 세션 식별자로 대체하지 않는다.

서버 간 계약은 [BTS 연동 명세](bts-integration.md), 저장 구조는 [DB 명세](erd.md)를 따른다.
