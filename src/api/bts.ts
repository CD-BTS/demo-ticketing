import { request, sleep, USE_MOCK_BTS } from './client'
import type { BehaviorEventPayload, BtsUser } from './types'

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
