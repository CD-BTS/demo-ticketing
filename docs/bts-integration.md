# BTS 연동 명세

## 서비스 경계

브라우저는 Demo 세션으로 예매하고 Demo 서버가 BTS 사용자·이벤트 API를 호출한다. BTS API Key는 서버 런타임에서만 사용하며 브라우저·이벤트 payload·로그에 넣지 않는다. Demo DB는 demo_ticketing_db, BTS DB는 fairticket_db로 분리한다.

| 구분 | 브라우저 → Demo | Demo → BTS |
|---|---|---|
| 사용자 등록 | POST /api/v1/users, {userId} | POST /api/v1/users, {serviceId,externalUserId,accountStatus} |
| 사용자 식별 | 공개 ID와 Bearer 세션 | BTS가 반환한 UUID |
| 인증 | Authorization: Bearer | X-API-Key |
| 이벤트 원천 | 화면 단계 보고와 실제 예약 요청 | 서버 journal·업무 트랜잭션·Outbox |

## 사용자 매핑

demo_users.public_user_id를 bts_external_user_id로 고정하고 bts_service_id로 namespace를 구분한다. 최초 전송 또는 원격 정책 조회 시 BTS 등록 API를 호출해 bts_user_id에 UUID를 저장한다. 로컬 로그인은 BTS 연결 없이 가능하다. 등록된 UUID는 자동으로 다른 값으로 덮어쓰지 않는다.

등록 serviceId는 공백이 아닌 최대 50자 문자열이고 인증 서비스와 같아야 한다. externalUserId는 공백이 아닌 최대 100자 문자열이다. accountStatus는 ACTIVE로 보낸다. BTS의 신규 201/COMMON_201과 기존 매핑 재사용 200/COMMON_200을 모두 허용한다. 응답 UUID와 serviceId/externalUserId를 검증한다.

Demo의 UNIQUE(service,external ID)·UNIQUE(BTS UUID)와 BTS의 서비스별 외부 ID 유일성을 이용한다. BTS 등록 성공 후 로컬 매핑 저장이 실패해도 같은 외부 ID로 재시도하며 새로운 사용자를 임의 생성하지 않는다.

## 이벤트 API

`POST /api/v1/events`에 단일 X-API-Key와 JSON body를 전달한다. 필드명은 camelCase이며 내부 metadata 키를 자동 변환하지 않는다.

| 필드 | 계약 |
|---|---|
| serviceId | 공백 불가, 최대 50자, 인증 서비스 일치 |
| externalEventId | 공백 불가, 최대 100자, 생성 후 고정 |
| userId | 같은 서비스에 속한 BTS UUID, 최대 50자 |
| eventType | BOOKING/CANCEL/CHECK_IN/BOOKING_ATTEMPT |
| bookingId | BOOKING/CANCEL에서 필수, 최대 100자; BOOKING_ATTEMPT는 top-level에서 생략 |
| ticketId | CHECK_IN에서 필수, 최대 100자 |
| occurredAt | offset 포함 ISO 8601, UTC 기준 1000~9999년, 초 단위; Demo는 Z 사용 |
| metadata | JSON object; 일반 이벤트는 null 허용, BOOKING_ATTEMPT는 단계 필드 필수 |

BTS는 `(serviceId,externalEventId)`로 중복을 구분한다. 같은 ID·같은 정규화 내용은 기존 eventId와 200, 신규는 201, 같은 ID·다른 내용은 409다. Demo는 성공 envelope와 eventId, 사용자·서비스·외부 ID·이벤트 타입·bookingId를 검증한 뒤 SENT로 기록한다.

## 이벤트 매핑

| 이벤트 | 생성 근거 | metadata |
|---|---|---|
| BOOKING | 유효한 hold로 예매 확정 commit | concertId,performanceStartsAt,performanceEndsAt,ticketIds,bookingDurationSeconds,paymentIdentifier,ipAddress, 연결된 경우 attemptId |
| CANCEL | CONFIRMED→CANCELLED commit | concertId,performanceStartsAt,performanceEndsAt,ticketIds, 연결된 경우 attemptId |
| BOOKING_ATTEMPT | 예매 시작·클라이언트 관측·서버 확인 단계 journal | attemptId,transitionId,concertId,stage,status,sequence,source,recordedAt 및 문맥 필드 |
| CHECK_IN | 서버 검표 생산자 미구현 | 향후 최초 유효 검표 사실과 ticketId 필요 |

bookingDurationSeconds는 hold 생성~서버 확정의 0 이상 number다. paymentIdentifier는 허용된 테스트 식별자이며 카드번호/hash를 대신 수집하지 않는다. ipAddress는 서버가 관측한 요청 peer다. Forwarded/X-Forwarded-For는 신뢰 프록시 설정 없이 사용하지 않는다. 공연 시각·ticketIds는 예매 snapshot에서 얻으며 실제 참석 사실을 의미하지 않는다.

일반 이벤트의 metadata.bookingDurationSeconds는 제공되면 0 이상 숫자, metadata.deviceChanged는 제공되면 boolean이어야 한다. Feature 원천 전달과 BTS Feature 계산은 별개다. 브라우저 클릭·QR 표시·선점 만료를 BOOKING/CANCEL/CHECK_IN으로 만들어 보내지 않는다.

BOOKING_ATTEMPT는 [예매 시도 명세](booking-attempts.md)를 따른다. COMPLETED/SUCCEEDED는 metadata.bookingId로 실제 확정 예매를 연결한다. BOOKING/CANCEL은 top-level bookingId를 유지하고 metadata.attemptId만 추가한다. 기존에 동결된 payload를 소급 수정하지 않는다.

## Outbox와 재시도

업무 상태 변경과 BOOKING/CANCEL Outbox 저장은 같은 로컬 트랜잭션이다. 단계는 journal에 저장한 뒤 별도 주기 작업에서 Outbox로 materialize한다. 원격 네트워크 I/O는 예약 잠금·DB 트랜잭션 밖에서 수행한다. 작업 기본 주기는 5초다.

1. READ COMMITTED 트랜잭션에서 해당 serviceId의 due 이벤트를 SELECT FOR UPDATE SKIP LOCKED로 claim한다.
2. IN_FLIGHT, 임의 lease token, 60초 locked_until을 기록하고 시도 횟수를 증가시킨다.
3. 사용자 UUID를 매핑하고 payload가 NULL일 때만 원천 metadata와 고정 ID·시각을 결합해 최종 body를 동결한다.
4. BTS에 같은 payload를 전송하고 lease token이 일치하는 행만 SENT/RETRY/FAILED로 갱신한다.

| 결과 | 처리 |
|---|---|
| 검증된 200/201 | SENT, bts_event_id·sent_at 저장 |
| 연결 실패·timeout·5xx | RETRY, 지수 backoff: 2초부터 최대 300초, 총 8회 시도 후 FAILED |
| 400/401/403/404/409 등 비재시도 오류 | 즉시 FAILED |
| 중단된 IN_FLIGHT | lease 만료 후 재claim |

HTTP 연결 timeout은 2초, 요청 timeout은 3초이며 redirect를 따라가지 않는다. 응답 형식 오류는 응답 상태와 검증 지점에 따라 재시도 또는 실패 처리한다. 오류 원문 대신 제한된 안전 코드만 저장한다.

같은 예매의 앞선 이벤트가 SENT가 되어야 CANCEL을 보낸다. FAILED BOOKING을 새 ID로 우회하지 않는다. BOOKING_ATTEMPT는 업무 그룹 선행 실패에 막히지 않고 독립 전송한다. 도착 순서는 보장하지 않으며 조회에서 sequence로 현재 단계를 복원한다. 재시도로 externalEventId·occurredAt·metadata·동결 payload를 바꾸지 않는다.

운영 재처리는 원인과 기존 BTS 레코드를 확인한 뒤 같은 ID/payload를 유지해야 한다. 공개 재처리 API는 없다. API Key 교체·UUID 초기화·BTS DB 직접 수정으로 오류를 우회하지 않는다.

## 원격 정책 API

POLICY_DECISION_MODE=bts는 BTS UUID를 확보하고 `POST /api/v1/users/{uuid}/policy-decisions`에 `{ "context": "QUEUE_JOIN" }`을 전송한다. X-API-Key는 서버 설정을 사용하며 `data.action`은 ALLOW/REQUIRE_CAPTCHA/DELAY/RESTRICT 중 하나여야 한다. latestAction 조회를 신규 결정 대신 사용하지 않는다.

정책 호출 코드와 원격 endpoint 배포는 별개의 조건이다. 배포 대상 BTS가 endpoint를 제공해야 bts 모드를 사용할 수 있다. 원격 장애·알 수 없는 응답은 입장 허용으로 바꾸지 않고 503으로 처리한다. disabled는 ALLOW, mock은 인증된 Demo 공개 ID에 대한 서버 설정을 사용한다. 활성 WAITING/ADMITTED는 정책을 재적용하지 않는다.

## 설정

| 설정 | 기본값 / 의미 |
|---|---|
| BOOKING_ATTEMPTS_ENABLED | false; attempt 스키마 적용 후 true |
| DEMO_JOBS_ENABLED | true; 만료 정리·단계 materialize·Outbox 전송 |
| BTS_ENABLED | false; false이면 이벤트 원천을 보존하고 네트워크 호출하지 않음 |
| BTS_BASE_URL | 활성 시 필수; HTTPS, 루프백 테스트만 HTTP 허용 |
| BTS_SERVICE_ID | demo-ticketing; 사용자/이벤트 namespace |
| BTS_API_KEY | 활성 시 필수; 런타임 비밀정보 |
| POLICY_DECISION_MODE | disabled/mock/bts |
| CAPTCHA_MODE | disabled/mock |

새 serviceId는 사용자·이벤트 생성 전에 결정한다. 이미 매핑된 기존 사용자의 namespace를 임의 변경하지 않는다. 스키마·마이그레이션은 [DB 명세](erd.md), 브라우저 계약은 [Demo API 명세](api-spec.md)를 따른다.
