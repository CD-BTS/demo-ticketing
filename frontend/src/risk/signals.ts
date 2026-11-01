/**
 * 브라우저에서 직접 잴 수 있는 행동 신호 (좌석 선택 소요 시간, 클릭 간격).
 * 카드/IP 집계, 관람 완료·노쇼, AI 이상 점수는 서버만 알 수 있어서 여기서 다루지 않는다.
 * 서버 Credit API가 연동되면 이 파일은 "서버에 보내는 이벤트 metadata"용으로만 남겨도 된다.
 */
interface Signals {
  seatSelectSec: number | null // 가장 최근 방문에서 첫 좌석 선택까지 걸린 시간(초)
  clicks: number[] // 최근 좌석 클릭 시각(ms), 최대 MAX_CLICKS개
}

const MAX_CLICKS = 10
const key = (userId: string) => `fds.signals.${userId}`

export function getSignals(userId: string): Signals {
  try {
    const raw = localStorage.getItem(key(userId))
    if (raw) return JSON.parse(raw) as Signals
  } catch {
    /* ignore */
  }
  return { seatSelectSec: null, clicks: [] }
}

function save(userId: string, s: Signals) {
  try {
    localStorage.setItem(key(userId), JSON.stringify(s))
  } catch {
    /* ignore */
  }
}

/** 좌석 클릭 1회 기록. firstSelectSec 는 좌석 화면 진입 후 첫 선택일 때만 넘긴다. */
export function recordSeatClick(userId: string, nowMs: number, firstSelectSec?: number) {
  const s = getSignals(userId)
  s.clicks = [...s.clicks, nowMs].slice(-MAX_CLICKS)
  if (firstSelectSec !== undefined) s.seatSelectSec = firstSelectSec
  save(userId, s)
}
