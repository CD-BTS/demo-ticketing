import { calcCredit, intervalVarianceSec2 } from '../risk/credit'
import { getSignals } from '../risk/signals'
import { request, sleep, USE_MOCK_BTS } from './client'
import { getAutoCancelCount } from './ticketing'
import type { AccountRisk, BehaviorEventPayload, BtsUser } from './types'

/** POST /api/v1/users — 사용자 등록 (구현 완료: sy kim) */
export async function registerUser(userId: string): Promise<BtsUser> {
  if (USE_MOCK_BTS) {
    await sleep(200)
    return { userId }
  }
  return request<BtsUser>('POST', '/users', { userId })
}

/** GET /api/v1/users/{userId} — 사용자 상세 조회 (구현 완료) */
export async function getUser(userId: string): Promise<BtsUser> {
  if (USE_MOCK_BTS) return { userId }
  return request<BtsUser>('GET', `/users/${encodeURIComponent(userId)}`)
}

/** POST /api/v1/events — 사용자 행동 이벤트 수집 (구현 완료) */
export async function sendBehaviorEvent(payload: BehaviorEventPayload): Promise<void> {
  if (USE_MOCK_BTS) {
    console.debug('[mock event]', payload.eventType, payload.metadata ?? '')
    return
  }
  await request<unknown>('POST', '/events', payload)
}

/**
 * 계정 등급 / 의심 점수 (Notion 산식, src/risk/credit.ts).
 *
 * 지금은 브라우저에서 잴 수 있는 값(좌석 선택 시간, 클릭 간격, 미입금 자동취소)만으로 계산한다.
 * 아래 값은 서버 연동 후 서버 값으로 교체:
 *  - attended / noShow : QR 검표 이후 서버 집계
 *  - anomaly           : GET /users/{id}/assessments/latest (Isolation Forest 이상 확률 0~1)
 *  - cardAccounts / ipAttempts10m : 서버·Redis 실시간 집계
 *  - 또는 GET /users/{id}/credit 이 점수·등급을 바로 주면 그 값을 그대로 표시
 */
export async function getAccountRisk(userId: string): Promise<AccountRisk> {
  const sig = getSignals(userId)
  return calcCredit({
    attended: 0,
    noShow: 0,
    autoCancel: getAutoCancelCount(userId),
    anomaly: 0,
    cardAccounts: 0,
    ipAttempts10m: 0,
    seatSelectSec: sig.seatSelectSec,
    intervalVariance: intervalVarianceSec2(sig.clicks),
  })
}
