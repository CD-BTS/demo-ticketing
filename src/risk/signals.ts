/**
 * 브라우저에서 직접 잴 수 있는 행동 기록 (Feature 산출의 원재료).
 * 카드/IP 집계, 관람 완료·노쇼, AI 이상 점수는 서버만 알 수 있어서 여기서 다루지 않는다.
 * 서버 Credit API가 연동되면 이 기록은 "서버로 보내는 이벤트 metadata"용으로만 남겨도 된다.
 */
export type RequestKind = 'select' | 'attempt' | 'pay'
export type StepKey = 'seat_select' | 'payment'

export interface RequestLog {
  t: number // 시각(ms)
  kind: RequestKind // select: 좌석 선택 / attempt: 예매하기(좌석 선점 시도) / pay: 결제 요청
}

export interface Signals {
  seatSelectSec: number | null // 좌석 배치도 로딩 완료 ~ 첫 좌석 선택(초): 가장 최근 방문 기준
  requests: RequestLog[] // 최근 10분의 예매 요청 기록
  stay: Record<StepKey, number | null> // 단계별 페이지 체류 시간(초): 가장 최근 방문 기준
}

export const WINDOW_MS = 10 * 60 * 1000
const MAX_LOG = 100
const key = (userId: string) => `fds.signals.v2.${userId}`

const empty = (): Signals => ({ seatSelectSec: null, requests: [], stay: { seat_select: null, payment: null } })

export function getSignals(userId: string): Signals {
  try {
    const raw = localStorage.getItem(key(userId))
    if (raw) {
      const s = JSON.parse(raw) as Partial<Signals>
      return {
        seatSelectSec: s.seatSelectSec ?? null,
        requests: Array.isArray(s.requests) ? s.requests : [],
        stay: { seat_select: s.stay?.seat_select ?? null, payment: s.stay?.payment ?? null },
      }
    }
  } catch {
    /* ignore */
  }
  return empty()
}

function save(userId: string, s: Signals) {
  try {
    localStorage.setItem(key(userId), JSON.stringify(s))
  } catch {
    /* ignore */
  }
}

/** 예매 요청 1건 기록. firstSelectSec 는 좌석 화면 진입 후 "첫" 좌석 선택일 때만 넘긴다. */
export function recordRequest(userId: string, kind: RequestKind, nowMs = Date.now(), firstSelectSec?: number) {
  const s = getSignals(userId)
  s.requests = [...s.requests, { t: nowMs, kind }].filter((r) => nowMs - r.t <= WINDOW_MS).slice(-MAX_LOG)
  if (firstSelectSec !== undefined) s.seatSelectSec = firstSelectSec
  save(userId, s)
}

/** 단계 화면을 떠날 때 그 단계에 머문 시간(초) 기록 */
export function recordStay(userId: string, step: StepKey, sec: number) {
  const s = getSignals(userId)
  s.stay[step] = Math.round(sec * 10) / 10
  save(userId, s)
}
