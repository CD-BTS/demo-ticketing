# ticket-fds-frontend (민채 · FE)

React + Vite + TypeScript. 1차(~10/30) 범위: 공연·좌석 UI, 예매·마이페이지 UI.

## 실행
```bash
npm install
cp .env.example .env
npm run dev      # http://localhost:5173
npm run build
```

## 화면
| 경로 | 화면 |
|---|---|
| (로그인 전) | 사용자 ID 입력 → `POST /api/v1/users` |
| `/` | 공연 목록 |
| `/concerts/:id` | 좌석 배치도 → 선점(5분 타이머) → 예매 확정 |
| `/my` | 예매 내역, 예매 취소 |

## mock ↔ 실제 API 전환
`.env` 두 스위치로 독립 전환한다.

- `VITE_USE_MOCK_BTS` : 사용자 등록 / 이벤트 수집 (`src/api/bts.ts`). 백엔드 연동 시 `false`.
- `VITE_USE_MOCK_TICKETING` : 공연·좌석·선점·예매 (`src/api/ticketing.ts`). 서연 API 확정 후 `false`.

`ticketing.ts` 의 `real` 객체에 **가정한** 엔드포인트가 들어 있다. 확정되는 대로 수정.
mock은 localStorage에 상태를 저장하고, 선점 만료 시 좌석이 AVAILABLE로 복구된다.

## 행동 이벤트 (`src/hooks/useBehavior.ts`)
`VIEW_CONCERT, VIEW_SEATS, SELECT_SEAT, HOLD_SEAT, HOLD_FAILED, BOOKING_CONFIRM, BOOKING_CANCEL`
→ `POST /api/v1/events`. 좌석 관련 이벤트에는 `elapsedMs`(좌석 화면 진입 후 경과 시간)를 실어 보낸다.

## BE/AI 팀에 확인할 것
1. `POST /events` 요청 body 필드(userId/eventType/deviceId/metadata는 **가정**)
2. 공연·좌석·선점·예매 API 경로와 응답 형태, 선점 유지 시간(현재 5분 가정)
3. 인증: 명세서는 Bearer Token인데 로그인/토큰 발급 API가 없음 (현재는 ID만 사용)
4. API 명세서의 "설명" 열이 기능과 한 칸씩 어긋나 있음 (예: 이벤트 수집 ↔ 대시보드 설명)
5. `/api` 요청은 개발 시 `localhost:8080`으로 프록시됨 (`vite.config.ts`)

## 다음 단계
- 2차(~11/06): 선점·예매 API 연동, 선점 실패/만료 UX 다듬기
- 3차(~11/13): 대기열 화면, 동적 QR 화면 (인영/서연 API 필요)
