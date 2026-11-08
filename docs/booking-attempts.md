# 예매 시도 이벤트 명세

## 식별자와 권한

attemptId는 소문자 정규 UUID이며 사용자·공연·서비스에 고정된다. 동일 ID의 같은 시작 요청은 재사용하고 다른 사용자나 공연에 붙일 수 없다. 선택 clientOccurredAt은 offset 포함 ISO 8601 문자열이며 최대 40자다. 클라이언트 시각과 서버의 started_at/recordedAt을 혼동하지 않는다.

transitionId는 클라이언트 전이·CAPTCHA 검증 재전송을 구분하는 UUID다. 서버 전이는 의미 있는 문자열 식별자를 생성한다. 같은 attempt/transition의 같은 내용은 기존 결과를 반환하고 다른 내용은 DATA_CONFLICT다. 각 새 전이는 attempt 행 잠금 아래 sequence를 증가시킨다.

## 단계와 상태

| 단계 | 기록 근거 |
|---|---|
| STARTED/ENTERED | 시작 API, sequence=1 |
| CAPTCHA/ENTERED | 서버 challenge 발급 |
| CAPTCHA/REJECTED | 식별자가 있는 검증 실패 요청 |
| CAPTCHA/SUCCEEDED | 서버 CAPTCHA 검증 성공 |
| QUEUE/ENTERED | 서버 관측 WAITING |
| QUEUE/SUCCEEDED | 서버 관측 ADMITTED |
| SEAT_SELECTION/ENTERED | 입장 후 클라이언트 화면 관측 |
| SEAT_HOLD/SUCCEEDED | 실제 hold 생성·연결 성공 |
| SEAT_HOLD/REJECTED | 좌석/입장 오류가 확인된 요청 |
| PAYMENT/ENTERED | 유효한 연결 hold를 가진 클라이언트 결제 화면 관측 |
| COMPLETED/SUCCEEDED | 실제 예매 확정·연결 bookingId |
| SEAT_HOLD/EXPIRED | DB에서 hold 만료 상태 확인 |

클라이언트는 SEAT_SELECTION/ENTERED와 PAYMENT/ENTERED를 보고할 수 있다. 좌석 화면은 QUEUE/SUCCEEDED 또는 기존 SEAT_SELECTION 상태에서, 결제 화면은 hold 연결이 있을 때 허용한다. 현재 단계의 ABANDONED도 가능하지만 연결 hold가 ACTIVE/CONVERTED이면 먼저 실제 업무 상태를 정리해야 한다. 클라이언트가 COMPLETED·업무 성공·FAILED를 임의로 보고할 수 없다.

COMPLETED 또는 FAILED/ABANDONED/EXPIRED는 terminal 상태다. terminal 이후 새 전이는 거부하지만 기존 같은 transition의 재전송은 허용한다. 취소 완료는 별도 CANCEL 이벤트이며 이미 완료된 attempt를 다시 진행 상태로 돌리지 않는다. 탭 종료·무응답·시간 경과만으로 이탈·실패를 추정하지 않는다.

## journal과 업무 연결

booking_attempts는 현재 stage/status/sequence와 nullable hold/booking을 저장한다. booking_attempt_events는 불변 metadata·fingerprint·sequence를 보관하고 outbox_id로 전송 원천을 연결한다. 같은 hold/booking을 여러 attempt에 붙이지 않도록 UNIQUE가 있다.

예약 확정·hold 연결·단계 저장은 로컬 트랜잭션의 일관성을 유지한다. Redis 성공과 MySQL 관측 기록은 분산 트랜잭션이 아니므로 단계 기록 실패가 입장을 되돌리지는 않는다. 다음 조회로 현재 상태를 다시 관측할 수 있으나 사라진 과거 상태의 완전 복원을 보장하지 않는다. DB의 booking/expired hold를 확인한 조회·주기 작업은 실제 상태로 reconcile한다.

## BTS metadata

필수 필드는 `attemptId,transitionId,concertId,stage,status,sequence,source,recordedAt`이다. source는 SERVER 또는 CLIENT_REPORT, sequence는 1 이상 정수다. STARTED/ENTERED는 sequence 1이며 COMPLETED는 SUCCEEDED와 실제 bookingId를 요구한다.

연결에 따라 holdId/bookingId, 확인된 실패 reasonCode, 시작 시 clientOccurredAt을 추가한다. 사용자 UUID는 최상위 userId, 서비스는 최상위 serviceId에 넣는다. BOOKING_ATTEMPT에는 가짜 top-level bookingId를 넣지 않는다. API Key·CAPTCHA 답/토큰·원문 카드번호·deviceId를 단계 metadata에 저장하지 않는다.

## 브라우저 재전송

브라우저는 사용자·공연별 sessionStorage에 attemptId·클릭 시각·transition ID·미확정 stage 요청을 전송 전에 저장한다. 동일 브라우저 탭의 새로고침과 응답 유실에는 같은 body로 재시도한다. 네트워크 오류·5xx·429는 최대 3회, 100/200ms 간격으로 시도하며 그 밖의 4xx를 자동 반복하지 않는다. 실패한 전이는 저장소에 남겨 다음 조회에서 다시 전송한다.

CAPTCHA 검증은 challenge/입력 SHA-256 digest에 transition UUID를 연결한다. 답 원문을 sessionStorage에 저장하지 않는다. 원래 grant TTL을 넘겨 재전송하더라도 유효 기간을 연장하거나 grant를 재발급하지 않는다.

화면은 서버 attempt·본인 hold·booking 조회를 통해 복원한다. SEAT_SELECTION/PAYMENT 기록 응답을 확인한 뒤 다음 동작을 허용한다. 명시적인 종료 후 새 예매는 새 attemptId를 사용한다. 저장소 삭제·탭 종료 후 브라우저에만 있던 미전송 요청 복구는 보장하지 않는다.

## BTS 조회

`GET /api/v1/events/booking-attempts?concertId=...&userId=...&page=0&size=20`은 X-API-Key의 서비스 범위에서 조회한다. userId는 BTS UUID다. page는 0~100000, size는 1~100이다.

data는 `{bookingAttemptCount,items}`이며 items에 attemptId/concertId/userId/startedAt/sequence/stage/status/bookingAttemptCount를 반환한다. count는 STARTED/ENTERED가 있는 고유 attemptId 기준이다. STARTED보다 뒤 단계가 먼저 도착하면 시작 이벤트가 올 때까지 집계·목록에서 제외하고, 현재 단계는 도착 순서가 아니라 가장 큰 sequence로 복원한다. 개별 전체 이력은 BTS 사용자 이벤트 조회의 BOOKING_ATTEMPT 필터를 사용한다.

## 활성화

BOOKING_ATTEMPTS_ENABLED 기본값은 false다. false에서는 capability=false, attempt API=503이며 attempt를 보내지 않는 기존 예매·대기열·CAPTCHA 경로는 유지한다. true는 [DB 명세](erd.md)의 attempt 스키마가 필요하다. 단계 Outbox 생성·전송은 DEMO_JOBS_ENABLED, 원격 전송은 BTS_ENABLED와 서버 인증 설정도 필요하다.
