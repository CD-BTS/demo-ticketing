// 실제 대기열의 WAITING 순번은 1부터 시작하며 ADMITTED만 입장을 허용한다.
import { request, sleep, USE_MOCK_TICKETING } from './client'

export interface QueueStatus {
  position: number
  behind: number
  status?: string
}
export interface QueueTicket extends QueueStatus {
  ticketId: string
}

export interface QueueApi {
  /** 대기열 진입 (같은 사용자·공연이면 기존 순번을 그대로 돌려준다) */
  join(concertId: string, userId: string, attemptId?: string, captchaToken?: string): Promise<QueueTicket>
  status(ticketId: string, attemptId?: string): Promise<QueueStatus>
  leave(ticketId: string): Promise<void>
}

const USE_MOCK_QUEUE = USE_MOCK_TICKETING && import.meta.env.VITE_USE_MOCK_QUEUE !== 'false'

// ---- mock -------------------------------------------------------
const STEP_MS = 1000 // 앞사람이 1명 빠지는 간격
const MIN_START = 4
const MAX_START = 9

interface MockTicket {
  ticketId: string
  startedAt: number
  start: number // 진입 시점의 내 앞 인원
  behindStart: number
}
const tickets = new Map<string, MockTicket>() // key: ticketId
const byUser = new Map<string, string>() // key: userId:concertId -> ticketId

// VITE_MOCK_QUEUE_START=숫자 로 시작 번호를 고정할 수 있다 (0 이면 대기 없이 바로 입장)
function startPosition(): number {
  const fixed = import.meta.env.VITE_MOCK_QUEUE_START
  if (fixed !== undefined && fixed !== '') return Math.max(0, Number(fixed) || 0)
  return MIN_START + Math.floor(Math.random() * (MAX_START - MIN_START + 1))
}

function snapshot(t: MockTicket): QueueStatus {
  const elapsed = Date.now() - t.startedAt
  return {
    position: Math.max(0, t.start - Math.floor(elapsed / STEP_MS)),
    behind: t.behindStart + Math.floor(elapsed / 2000), // 뒤에 계속 사람이 들어오는 것처럼
  }
}

const mock: QueueApi = {
  async join(concertId, userId) {
    await sleep(120)
    const k = `${userId}:${concertId}`
    const existing = byUser.get(k) && tickets.get(byUser.get(k)!)
    if (existing) return { ticketId: existing.ticketId, ...snapshot(existing) }
    const t: MockTicket = {
      ticketId: `q_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`,
      startedAt: Date.now(),
      start: startPosition(),
      behindStart: 20 + Math.floor(Math.random() * 100),
    }
    tickets.set(t.ticketId, t)
    byUser.set(k, t.ticketId)
    return { ticketId: t.ticketId, ...snapshot(t) }
  },
  async status(ticketId) {
    const t = tickets.get(ticketId)
    if (!t) return { position: 0, behind: 0 }
    return snapshot(t)
  },
  async leave(ticketId) {
    tickets.delete(ticketId)
    for (const [k, v] of byUser) if (v === ticketId) byUser.delete(k)
  },
}

interface ServerQueue { status: string; position: number | null; expiresAt: string | null }
const headers = (attemptId?: string, captchaToken?: string): Record<string, string> => ({
  ...(attemptId ? { 'X-Booking-Attempt': attemptId } : {}),
  ...(captchaToken ? { 'X-Captcha-Token': captchaToken } : {}),
})
const adapt = (s: ServerQueue): QueueStatus => ({ status: s.status, position: s.status === 'ADMITTED' ? 0 : s.position ?? -1, behind: 0 })
const real: QueueApi = {
  async join(concertId, _user, attemptId, captchaToken) {
    const s = await request<ServerQueue>('POST', `/concerts/${concertId}/queue`, undefined, headers(attemptId, captchaToken))
    return { ticketId: concertId, ...adapt(s) }
  },
  async status(concertId, attemptId) { return adapt(await request<ServerQueue>('GET', `/concerts/${concertId}/queue/me`, undefined, headers(attemptId))) },
  leave: concertId => request('DELETE', `/concerts/${concertId}/queue/me`),
}

export interface CaptchaChallenge { challengeId: string; question: string; expiresAt: string }
export const captchaChallenge = (concert: string, attempt: string | undefined) => request<CaptchaChallenge>('POST', `/concerts/${concert}/queue/captcha-challenges`, undefined, headers(attempt))
export const captchaVerify = (concert: string, attempt: string | undefined, challengeId: string, answer: string, transition: string) => request<{ captchaToken: string; expiresAt: string }>('POST', `/concerts/${concert}/queue/captcha-verifications`, { challengeId, answer }, { ...headers(attempt), 'X-Attempt-Transition': transition })

export const queueApi: QueueApi = USE_MOCK_QUEUE ? mock : real

// ---- 입장 처리 ---------------------------------------------------
/**
 * 대기열을 통과한 뒤 결제 화면에 갔다가 돌아와도 다시 줄서지 않도록 입장 기록을 잠시 보관한다.
 * (실제 서비스에서는 서버가 발급한 입장 토큰으로 대체되어야 한다. 이 값은 화면 편의용일 뿐 보안 수단이 아니다.)
 */
const ADMIT_MS = 15 * 60 * 1000
const admitKey = (userId: string, concertId: string) => `fds.admit.${userId}.${concertId}`

export function markAdmitted(userId: string, concertId: string) {
  try {
    sessionStorage.setItem(admitKey(userId, concertId), String(Date.now() + ADMIT_MS))
  } catch {
    /* ignore */
  }
}

export function isAdmitted(userId: string, concertId: string): boolean {
  try {
    return Number(sessionStorage.getItem(admitKey(userId, concertId)) ?? 0) > Date.now()
  } catch {
    return false
  }
}

export function clearAdmitted(userId: string, concertId: string) {
  try {
    sessionStorage.removeItem(admitKey(userId, concertId))
  } catch {
    /* ignore */
  }
}
