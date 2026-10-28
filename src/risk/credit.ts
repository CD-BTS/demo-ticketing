/**
 * BTS Credit(신용 점수) / 의심 점수 산출.
 * 출처: Notion "교수님 피드백 2차" — "Credit + Isolation Forest 감점 요인 통합본" 중 최종 "수정된 표".
 *
 *   Credit_final = max(0, BaseCredit − DynamicPenalty)
 *   BaseCredit   = 100 × (A + α) / (A + 2.0·N + 1.5·Cauto + α + β)      (α = β = 5)
 *   DynamicPenalty = P_IF + P_card + P_ip + P_speed + P_variance
 *
 * ⚠️ 감점 가중치는 PDF에서 "실험으로 선정할 후보값"이라고 되어 있다.
 *    확정되면 아래 CREDIT_PARAMS 만 고치면 된다.
 * ⚠️ 등급 구간(GRADE_TIERS)과 "의심 점수 = DynamicPenalty" 정의는 PDF에 명시가 없어 임시로 정한 값이다.
 */

export const CREDIT_PARAMS = {
  alpha: 5,
  beta: 5,
  noShowWeight: 2.0, // 노쇼 1건 = 2.0
  autoCancelWeight: 1.5, // 선점 후 미입금 자동 취소 1건 = 1.5
  ifMaxPenalty: 40, // P_IF = S_anomaly × 40
  card: { strong: { min: 4, points: 15 }, weak: { min: 2, points: 5 } }, // 최근 30일 동일 카드 연결 계정 수
  ip: { strong: { min: 5, points: 5 } }, // 동일 IP 최근 10분 내 예매 시도 수
  speed: { belowSec: 1.0, points: 5 }, // 좌석 선택 소요 시간(초)
  variance: { belowSec2: 1.0, points: 5, minRequests: 5 }, // 클릭 간격 분산(초²), 최근 5회 이상 요청 기준
} as const

/** 임시 등급 구간 (PDF 예시: 70점=우수, 45점=안전/경미 주의, 0점=3단계 패널티에서 역산). 확정 시 교체. */
export const GRADE_TIERS = [
  { grade: '우수', min: 70 },
  { grade: '안전', min: 40 },
  { grade: '주의', min: 20 },
  { grade: '위험', min: 0 },
] as const
export type CreditGrade = (typeof GRADE_TIERS)[number]['grade']

export interface CreditInput {
  attended: number // A  정상 관람 완료 건수 (QR 검표 후 서버가 제공)
  noShow: number // N  노쇼 건수
  autoCancel: number // Cauto  선점 후 미입금 자동 취소 건수
  anomaly: number // S_anomaly  Isolation Forest 이상 확률 0~1 (AI 서버 결과)
  cardAccounts: number // K_card  최근 30일 동일 카드에 연결된 계정 수 (서버 집계)
  ipAttempts10m: number // K_ip  동일 IP 최근 10분 내 예매 시도 수 (서버 집계)
  seatSelectSec: number | null // T_seat  좌석 선택 소요 시간(초), 없으면 null
  intervalVariance: number | null // σ²_interval  클릭 간격 분산(초²), 요청 5회 미만이면 null
}

export interface PenaltyItem {
  key: 'IF' | 'card' | 'ip' | 'speed' | 'variance'
  label: string
  points: number
}

export interface CreditResult {
  baseCredit: number
  penalties: PenaltyItem[]
  suspicionScore: number // = DynamicPenalty
  credit: number // Credit_final
  grade: CreditGrade
}

export const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const round1 = (v: number) => Math.round(v * 10) / 10

export function baseCredit(i: Pick<CreditInput, 'attended' | 'noShow' | 'autoCancel'>): number {
  const { alpha, beta, noShowWeight, autoCancelWeight } = CREDIT_PARAMS
  const denom = i.attended + noShowWeight * i.noShow + autoCancelWeight * i.autoCancel + alpha + beta
  return (100 * (i.attended + alpha)) / denom
}

export function dynamicPenalties(i: CreditInput): PenaltyItem[] {
  const P = CREDIT_PARAMS
  const pIF = clamp(i.anomaly, 0, 1) * P.ifMaxPenalty
  const pCard = i.cardAccounts >= P.card.strong.min ? P.card.strong.points : i.cardAccounts >= P.card.weak.min ? P.card.weak.points : 0
  const pIp = i.ipAttempts10m >= P.ip.strong.min ? P.ip.strong.points : 0
  const pSpeed = i.seatSelectSec !== null && i.seatSelectSec < P.speed.belowSec ? P.speed.points : 0
  const pVar = i.intervalVariance !== null && i.intervalVariance < P.variance.belowSec2 ? P.variance.points : 0
  return [
    { key: 'IF', label: 'AI 이상 탐지 (Isolation Forest)', points: round1(pIF) },
    { key: 'card', label: '동일 카드 다계정 사용', points: pCard },
    { key: 'ip', label: '동일 IP 집중 시도', points: pIp },
    { key: 'speed', label: '비정상적으로 빠른 좌석 선택', points: pSpeed },
    { key: 'variance', label: '기계적으로 균일한 클릭 간격', points: pVar },
  ]
}

export function gradeOf(credit: number): CreditGrade {
  return (GRADE_TIERS.find((t) => credit >= t.min) ?? GRADE_TIERS[GRADE_TIERS.length - 1]).grade
}

export function calcCredit(input: CreditInput): CreditResult {
  const base = baseCredit(input)
  const penalties = dynamicPenalties(input)
  const suspicion = round1(penalties.reduce((s, p) => s + p.points, 0))
  const credit = round1(Math.max(0, base - suspicion))
  return { baseCredit: round1(base), penalties, suspicionScore: suspicion, credit, grade: gradeOf(credit) }
}

/** 클릭 간격 분산(초²). 요청이 minRequests 미만이면 null (판단 불가 → 감점 없음) */
export function intervalVarianceSec2(timestampsMs: number[]): number | null {
  if (timestampsMs.length < CREDIT_PARAMS.variance.minRequests) return null
  const gaps: number[] = []
  for (let k = 1; k < timestampsMs.length; k++) gaps.push((timestampsMs[k] - timestampsMs[k - 1]) / 1000)
  const mean = gaps.reduce((s, g) => s + g, 0) / gaps.length
  return gaps.reduce((s, g) => s + (g - mean) ** 2, 0) / gaps.length
}
