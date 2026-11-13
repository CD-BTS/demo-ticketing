import { applyServerPolicy, calcCredit, type ServerPolicy } from '../risk/credit'
import { computeFeatures, toCreditInput } from '../risk/features'
import { getSignals } from '../risk/signals'
import { request, sleep, tokenStore, USE_MOCK_BTS, USE_MOCK_TICKETING } from './client'
import { getAutoCancelCount, ticketing } from './ticketing'
import type { AccountRisk, BehaviorEventPayload, BtsUser } from './types'

/** Demo 세션을 발급하고 브라우저 인증 토큰을 저장한다. */
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

/** BTS 사용자 상세 조회. Demo 백엔드에는 해당 조회 경로가 없다. */
export async function getUser(userId: string): Promise<BtsUser> {
  if (USE_MOCK_BTS) return { userId }
  return request<BtsUser>('GET', `/users/${encodeURIComponent(userId)}`)
}

/** 브라우저 텔레메트리는 서버가 생성하는 업무 이벤트와 구분한다. */
export async function sendBehaviorEvent(payload: BehaviorEventPayload): Promise<void> {
  // Demo 업무·예매 단계 이벤트는 서버가 관측해 Outbox로 전송한다.
  if (USE_MOCK_BTS || !USE_MOCK_TICKETING) {
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
 *
 * 화면에 표시하는 등급(applied)은 이 참고 산정이 아니라 서버 정책(GET /users/me/policy)을 따른다.
 * 대기열의 CAPTCHA·후순위가 같은 정책으로 정해지므로, 등급 표시와 실제 제재가 어긋나지 않는다.
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
  if (USE_MOCK_TICKETING) return { ...result, features, applied: applyServerPolicy(result, null) }
  try {
    const server = await request<ServerPolicy>('GET', '/users/me/policy')
    return { ...result, features, applied: applyServerPolicy(result, server) }
  } catch {
    return { ...result, features, applied: null }
  }
}
