import { calcCredit } from '../risk/credit'
import { computeFeatures, toCreditInput } from '../risk/features'
import { getSignals } from '../risk/signals'
import { request, sleep, tokenStore, USE_MOCK_BTS, USE_MOCK_TICKETING } from './client'
import { getAutoCancelCount, ticketing } from './ticketing'
import type { AccountRisk, BehaviorEventPayload, BtsUser } from './types'

/** POST /api/v1/users — 사용자 등록 (구현 완료: sy kim) */
export async function registerUser(userId: string): Promise<BtsUser> {
  if (USE_MOCK_BTS) {
    await sleep(200)
    return { userId }
  }
  // 데모 백엔드: 응답 {userId, token, expiresAt}. token 을 저장해 이후 요청에 Bearer 로 붙인다.
  const res = await request<BtsUser & { token?: string }>('POST', '/users', { userId })
  if (res.token) tokenStore.set(res.token)
  return res
}

/** GET /api/v1/users/{userId} — 사용자 상세 조회 (구현 완료) */
export async function getUser(userId: string): Promise<BtsUser> {
  if (USE_MOCK_BTS) return { userId }
  return request<BtsUser>('GET', `/users/${encodeURIComponent(userId)}`)
}

/** POST /api/v1/events — 사용자 행동 이벤트 수집 (구현 완료) */
export async function sendBehaviorEvent(payload: BehaviorEventPayload): Promise<void> {
  // 데모 백엔드는 브라우저의 9종 행동 이벤트를 받지 않는다(서버가 BOOKING/CANCEL만 BTS로 전송).
  if (USE_MOCK_BTS || !USE_MOCK_TICKETING) {
    console.debug('[event skipped]', payload.eventType, payload.metadata ?? '')
    return
  }
  await request<unknown>('POST', '/events', payload)
}

/**
 * Credit(신용 점수) · 계정 등급 · 의심 점수 (산식: src/risk/credit.ts, Feature: src/risk/features.ts).
 *
 * 지금은 브라우저에서 잴 수 있는 Feature(예매 간격, 좌석 선택 시간, 체류 시간, 시도 횟수, 취소율)와
 * 미입금 자동취소(mock)만으로 계산한다. 아래 값은 서버 연동 후 서버 값으로 교체:
 *  - attended / noShow : QR 검표 이후 서버 집계
 *  - anomaly           : GET /users/{id}/assessments/latest (Isolation Forest 이상 점수 0~1)
 *  - card_reuse_count / ip_booking_density : 서버·Redis 실시간 집계
 *  - 또는 GET /users/{id}/credit 이 base_credit·dynamic_penalty·credit_score·credit_grade·penalty_policy 를
 *    바로 주면 그 값을 그대로 표시하면 된다.
 */
export async function getAccountRisk(userId: string): Promise<AccountRisk> {
  let bookings: { status: string; createdAt: string }[] = []
  try {
    bookings = await ticketing.listMyBookings(userId)
  } catch {
    /* 예매 목록을 못 받아도 나머지 Feature 로 계산한다 */
  }
  const features = computeFeatures(getSignals(userId), bookings)
  const result = calcCredit(
    toCreditInput(features, {
      attended: 0,
      noShow: 0,
      autoCancel: getAutoCancelCount(userId),
      anomaly: 0,
    }),
  )
  return { ...result, features }
}
