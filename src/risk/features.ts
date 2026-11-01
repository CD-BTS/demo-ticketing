/**
 * 이상 탐지 Feature 8종 (분류 · 정의 · 기준 후보).
 * 출처: Credit 기준 문서와 Feature 산출 방식 표.
 *
 * - 브라우저에서 잴 수 있는 6종은 아래 computeFeatures 로 계산한다.
 * - card_reuse_count / ip_booking_density 는 결제 수단·IP 를 아는 서버만 집계할 수 있다.
 * - "기준 후보"는 Isolation Forest / 규칙 임계값을 실험으로 고르기 위한 후보다. Credit 규칙 감점에는
 *   그중 확정 초기값(좌석 1초 미만, 분산 1.0초² 미만, 동일 IP 5회, 동일 카드 4계정)만 쓰인다 (src/risk/credit.ts).
 * - 서버는 behavior_event 로그에서 같은 Feature 를 다시 계산한다. 그래서 프론트는 이벤트에 원재료
 *   (발생 시각, elapsedMs 등)를 정확히 실어 보내는 것이 중요하다 (src/hooks/useBehavior.ts).
 */
import { CREDIT_PARAMS, type CreditInput } from './credit'
import { WINDOW_MS, type Signals } from './signals'

export interface BehaviorFeatures {
  avg_booking_interval: number | null // 초
  booking_interval_variance: number | null // 초²
  seat_selection_latency: number | null // 초
  step_stay_time: { seat_select: number | null; payment: number | null } // 초
  recent_booking_count: { last_1m: number; last_10m: number } // 회
  cancel_rate: number | null // 0~1
  card_reuse_count: number | null // 서버 집계 (없으면 null)
  ip_booking_density: number | null // 서버 집계 (없으면 null)
}
export type FeatureKey = keyof BehaviorFeatures

export interface FeatureSpec {
  key: FeatureKey
  group: string // 분류
  label: string
  definition: string // 정의 및 산출 방식
  candidates: string // 기준 후보
  source: 'browser' | 'server'
}

export const FEATURE_SPECS: FeatureSpec[] = [
  { key: 'avg_booking_interval', group: '시간 간격', label: '평균 예매 간격', definition: '연속된 예매 요청 간 시간 차이의 평균', candidates: '3 / 5 / 10초', source: 'browser' },
  { key: 'booking_interval_variance', group: '시간 간격', label: '예매 간격 분산', definition: '연속된 예매 요청 간격의 분산', candidates: '0.5 / 1.0 / 2.0초²', source: 'browser' },
  { key: 'seat_selection_latency', group: '체류 시간', label: '좌석 선택 소요 시간', definition: '좌석 배치도 로딩 완료부터 좌석 선택까지 걸린 시간', candidates: '0.5 / 1 / 3초', source: 'browser' },
  { key: 'step_stay_time', group: '체류 시간', label: '단계별 체류 시간', definition: '예매 단계별 페이지 체류 시간', candidates: '정상 분포 하위 5% / 10%', source: 'browser' },
  { key: 'recent_booking_count', group: '요청 빈도', label: '단시간 예매 시도 수', definition: '최근 1분 또는 10분 동안의 예매 시도 횟수', candidates: '1분간 5 / 10 / 20회', source: 'browser' },
  { key: 'cancel_rate', group: '취소 비율', label: '취소율', definition: '최근 30일 취소 건수 ÷ 예매 건수', candidates: '30 / 50 / 70%', source: 'browser' },
  { key: 'card_reuse_count', group: '카드 공유', label: '카드 공유 계정 수', definition: '최근 30일 동일 결제 수단을 사용한 서로 다른 계정 수', candidates: '4계정 이상', source: 'server' },
  { key: 'ip_booking_density', group: 'IP 집중도', label: 'IP 예매 집중도', definition: '동일 IP에서 최근 10분 동안 발생한 예매 시도 횟수', candidates: '5회 이상', source: 'server' },
]

const DAY_MS = 24 * 60 * 60 * 1000

/** "예매 시도" = 예매하기(좌석 선점 요청) 버튼 */
const isAttempt = (k: string) => k === 'attempt'

export function computeFeatures(
  sig: Signals,
  bookings: { status: string; createdAt: string }[],
  now = Date.now(),
): BehaviorFeatures {
  // 최근 10분의 예매 요청(좌석 선택·예매하기·결제)을 시간순으로
  const reqs = sig.requests.filter((r) => now - r.t <= WINDOW_MS).sort((a, b) => a.t - b.t)

  // 연속된 요청 간 간격(초)
  const gaps: number[] = []
  for (let i = 1; i < reqs.length; i++) gaps.push((reqs[i].t - reqs[i - 1].t) / 1000)
  const avg = gaps.length ? gaps.reduce((s, g) => s + g, 0) / gaps.length : null
  // 분산은 최근 minRequests(5)회 이상 요청이 있을 때만 의미가 있다 (그보다 적으면 판단 불가 → null)
  const variance =
    reqs.length >= CREDIT_PARAMS.variance.minRequests && avg !== null
      ? gaps.reduce((s, g) => s + (g - avg) ** 2, 0) / gaps.length
      : null

  const attempts = sig.requests.filter((r) => isAttempt(r.kind))
  const recent = bookings.filter((b) => now - new Date(b.createdAt).getTime() <= 30 * DAY_MS)
  const cancelled = recent.filter((b) => b.status === 'CANCELLED').length

  return {
    avg_booking_interval: avg === null ? null : round(avg, 2),
    booking_interval_variance: variance === null ? null : round(variance, 3),
    seat_selection_latency: sig.seatSelectSec === null ? null : round(sig.seatSelectSec, 2),
    step_stay_time: { ...sig.stay },
    recent_booking_count: {
      last_1m: attempts.filter((r) => now - r.t <= 60 * 1000).length,
      last_10m: attempts.filter((r) => now - r.t <= WINDOW_MS).length,
    },
    cancel_rate: recent.length ? cancelled / recent.length : null,
    card_reuse_count: null, // TODO: 서버 집계값 연동
    ip_booking_density: null, // TODO: 서버 집계값 연동
  }
}

/** Feature → Credit 산식 입력 (서버 집계값이 없으면 감점 없음으로 처리) */
export function toCreditInput(
  f: BehaviorFeatures,
  extra: Pick<CreditInput, 'attended' | 'noShow' | 'autoCancel' | 'anomaly'>,
): CreditInput {
  return {
    ...extra,
    cardAccounts: f.card_reuse_count ?? 0,
    ipAttempts10m: f.ip_booking_density ?? 0,
    seatSelectSec: f.seat_selection_latency,
    intervalVariance: f.booking_interval_variance,
  }
}

const round = (v: number, d: number) => Math.round(v * 10 ** d) / 10 ** d

/** 화면 표시용 문자열 */
export function formatFeature(f: BehaviorFeatures, key: FeatureKey): string {
  const dash = '—'
  switch (key) {
    case 'avg_booking_interval':
      return f.avg_booking_interval === null ? dash : `${f.avg_booking_interval}초`
    case 'booking_interval_variance':
      return f.booking_interval_variance === null ? dash : `${f.booking_interval_variance}초²`
    case 'seat_selection_latency':
      return f.seat_selection_latency === null ? dash : `${f.seat_selection_latency}초`
    case 'step_stay_time': {
      const { seat_select: a, payment: b } = f.step_stay_time
      return a === null && b === null ? dash : `좌석 ${a ?? dash}초 · 결제 ${b ?? dash}초`
    }
    case 'recent_booking_count':
      return `1분 ${f.recent_booking_count.last_1m}회 · 10분 ${f.recent_booking_count.last_10m}회`
    case 'cancel_rate':
      return f.cancel_rate === null ? dash : `${Math.round(f.cancel_rate * 100)}%`
    case 'card_reuse_count':
      return f.card_reuse_count === null ? '서버 집계 필요' : `${f.card_reuse_count}계정`
    case 'ip_booking_density':
      return f.ip_booking_density === null ? '서버 집계 필요' : `${f.ip_booking_density}회`
  }
}
