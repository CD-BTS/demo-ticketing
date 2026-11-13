/**
 * BTS Credit(신용 점수) 산출.
 * 출처: "Credit 기준 및 산출 방식" (Credit 산출 및 사용자 등급별 제재 정책)
 *
 *   Credit_final   = max(0, BaseCredit − DynamicPenalty)
 *   BaseCredit     = 100 × (A + α) / (A + N + Cauto + α + β)          α = β = 5, A·N·Cauto 는 가중치 반영 값
 *   DynamicPenalty = P_IF + P_Rule
 *   P_IF           = S_anomaly(0~1) × 40                               (최대 40점, 실험 후보 20/30/40)
 *   P_Rule         = 카드 + IP + 좌석 선택 속도 + 요청 간격 분산      (최대 30점)
 *
 * ⚠️ 감점 가중치·등급 임계값(40, 20)은 문서상 "초기 정책값이며 실험 결과에 따라 변경"된다.
 *    확정되면 CREDIT_PARAMS / GRADE_TIERS 만 고치면 된다.
 */

export const CREDIT_PARAMS = {
  alpha: 5,
  beta: 5,
  attendedWeight: 1.0, // 정상 관람 완료 ×1.0
  noShowWeight: 2.0, // 노쇼 ×2.0
  autoCancelWeight: 1.5, // 미입금 자동 취소 ×1.5  (일반 취소는 감점하지 않음)
  ifMaxPenalty: 40, // P_IF = S_anomaly × 40
  card: { strong: { min: 4, points: 15 }, weak: { min: 2, points: 5 } }, // 동일 카드 공유 계정 수 (card_reuse_count)
  ip: { strong: { min: 5, points: 5 } }, // 동일 IP 최근 10분 예매 시도 수 (ip_booking_density)
  speed: { belowSec: 1.0, points: 5 }, // 좌석 선택 소요 시간 (seat_selection_latency)
  variance: { belowSec2: 1.0, points: 5, minRequests: 5 }, // 요청 간격 분산 (booking_interval_variance), 최근 5회 이상 요청 기준
} as const

/** 등급 기준: 정상 40~100 / 의심 20~39 / 위험 0~19 (코드값은 문서의 NORMAL / SUSPICIOUS / HIGH_RISK) */
export const GRADE_TIERS = [
  { code: 'NORMAL', label: '정상', min: 40, policy: '일반 입장' },
  { code: 'SUSPICIOUS', label: '의심', min: 20, policy: 'CAPTCHA' },
  { code: 'HIGH_RISK', label: '위험', min: 0, policy: 'CAPTCHA + 후순위' },
] as const
export type CreditGradeCode = (typeof GRADE_TIERS)[number]['code']
export type CreditGrade = (typeof GRADE_TIERS)[number]['label'] // '정상' | '의심' | '위험'
export type PenaltyPolicy = (typeof GRADE_TIERS)[number]['policy'] // '일반 입장' | 'CAPTCHA' | 'CAPTCHA + 후순위'

export interface CreditInput {
  attended: number // A  정상 관람 완료 횟수 (QR 검표 후 서버가 제공)
  noShow: number // N  노쇼 횟수
  autoCancel: number // Cauto  선점 후 미입금 자동 취소 횟수
  anomaly: number // S_anomaly  Isolation Forest 정규화 이상 점수 0~1 (AI 서버 결과)
  cardAccounts: number // card_reuse_count  최근 30일 동일 카드 공유 계정 수 (서버 집계)
  ipAttempts10m: number // ip_booking_density  동일 IP 최근 10분 예매 시도 수 (서버 집계)
  seatSelectSec: number | null // seat_selection_latency  좌석 선택 소요 시간(초), 없으면 null
  intervalVariance: number | null // booking_interval_variance  요청 간격 분산(초²), 요청 5회 미만이면 null
}

export interface PenaltyItem {
  key: 'IF' | 'card' | 'ip' | 'speed' | 'variance'
  group: 'AI' | 'RULE'
  label: string
  points: number
}

/** 최종 산출물: base_credit / dynamic_penalty / credit_score / credit_grade / penalty_policy */
export interface CreditResult {
  baseCredit: number // base_credit
  penalties: PenaltyItem[]
  aiPenalty: number // P_IF
  rulePenalty: number // P_Rule
  dynamicPenalty: number // dynamic_penalty (= P_IF + P_Rule). 화면의 "의심 점수"
  credit: number // credit_score
  gradeCode: CreditGradeCode // credit_grade (NORMAL / SUSPICIOUS / HIGH_RISK)
  grade: CreditGrade // 정상 / 의심 / 위험
  policy: PenaltyPolicy // penalty_policy
}

export const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))
const round1 = (v: number) => Math.round(v * 10) / 10

export function baseCredit(i: Pick<CreditInput, 'attended' | 'noShow' | 'autoCancel'>): number {
  const P = CREDIT_PARAMS
  const A = i.attended * P.attendedWeight
  const N = i.noShow * P.noShowWeight
  const C = i.autoCancel * P.autoCancelWeight
  return (100 * (A + P.alpha)) / (A + N + C + P.alpha + P.beta)
}

export function dynamicPenalties(i: CreditInput): PenaltyItem[] {
  const P = CREDIT_PARAMS
  const pIF = clamp(i.anomaly, 0, 1) * P.ifMaxPenalty
  const pCard = i.cardAccounts >= P.card.strong.min ? P.card.strong.points : i.cardAccounts >= P.card.weak.min ? P.card.weak.points : 0
  const pIp = i.ipAttempts10m >= P.ip.strong.min ? P.ip.strong.points : 0
  const pSpeed = i.seatSelectSec !== null && i.seatSelectSec < P.speed.belowSec ? P.speed.points : 0
  const pVar = i.intervalVariance !== null && i.intervalVariance < P.variance.belowSec2 ? P.variance.points : 0
  return [
    { key: 'IF', group: 'AI', label: 'AI 이상 행동 (Isolation Forest)', points: round1(pIF) },
    { key: 'card', group: 'RULE', label: '동일 카드 다계정 공유', points: pCard },
    { key: 'ip', group: 'RULE', label: '동일 IP 집중 예매 시도', points: pIp },
    { key: 'speed', group: 'RULE', label: '좌석 선택 속도 (1초 미만)', points: pSpeed },
    { key: 'variance', group: 'RULE', label: '요청 간격 분산 (1.0초² 미만)', points: pVar },
  ]
}

export function gradeTierOf(credit: number) {
  return GRADE_TIERS.find((t) => credit >= t.min) ?? GRADE_TIERS[GRADE_TIERS.length - 1]
}

export function calcCredit(input: CreditInput): CreditResult {
  const base = baseCredit(input)
  const penalties = dynamicPenalties(input)
  const sum = (g: PenaltyItem['group']) => round1(penalties.filter((p) => p.group === g).reduce((s, p) => s + p.points, 0))
  const aiPenalty = sum('AI')
  const rulePenalty = sum('RULE')
  const dynamicPenalty = round1(aiPenalty + rulePenalty)
  const credit = round1(Math.max(0, base - dynamicPenalty))
  const tier = gradeTierOf(credit)
  return {
    baseCredit: round1(base),
    penalties,
    aiPenalty,
    rulePenalty,
    dynamicPenalty,
    credit,
    gradeCode: tier.code,
    grade: tier.label,
    policy: tier.policy,
  }
}

// ---- 서버 정책 반영 ----
// 대기열의 CAPTCHA·후순위는 서버 정책 결정(action)으로 정해진다. 화면의 등급도 같은 출처를 따라야
// "CAPTCHA는 뜨는데 등급은 정상"처럼 어긋나지 않는다. 위의 브라우저 산식은 참고값으로만 남긴다.

export type PolicyAction = 'ALLOW' | 'REQUIRE_CAPTCHA' | 'DELAY' | 'RESTRICT'

/** GET /users/me/policy 응답. source: NONE(정책 미사용) / MOCK(서버 로컬 설정) / BTS(원격 FDS) */
export interface ServerPolicy {
  source: 'NONE' | 'MOCK' | 'BTS'
  action: PolicyAction | null
  creditScore: number | null
  tier: string | null
  anomalyScore: number | null
}

/** 화면에 표시할 최종 등급. fromServer 가 false 면 브라우저 참고값 그대로다. */
export interface AppliedStanding {
  gradeCode: CreditGradeCode
  grade: CreditGrade
  policy: string
  credit: number | null // 서버가 준 신용 점수. 서버가 점수를 주지 않으면(mock 정책) null
  fromServer: boolean
  source: ServerPolicy['source']
}

const ACTION_STANDING: Record<PolicyAction, { code: CreditGradeCode; policy: string }> = {
  ALLOW: { code: 'NORMAL', policy: '일반 입장' },
  REQUIRE_CAPTCHA: { code: 'SUSPICIOUS', policy: 'CAPTCHA' },
  DELAY: { code: 'HIGH_RISK', policy: 'CAPTCHA + 후순위' },
  RESTRICT: { code: 'HIGH_RISK', policy: '예매 제한' },
}

const severity = (code: CreditGradeCode) => GRADE_TIERS.findIndex((t) => t.code === code)

/**
 * 서버 정책이 있으면 그 값으로 등급을 정한다. action 과 신용 점수가 서로 다른 등급을 가리키면
 * 더 엄격한 쪽을 표시한다(예: 신용 점수는 정상이지만 AI 이상 점수 때문에 CAPTCHA 가 걸린 경우).
 */
export function applyServerPolicy(local: CreditResult, server: ServerPolicy | null): AppliedStanding {
  const byAction = server?.action ? ACTION_STANDING[server.action] : null
  const byCredit = typeof server?.creditScore === 'number' ? gradeTierOf(server.creditScore) : null
  if (!server || (!byAction && !byCredit)) {
    return {
      gradeCode: local.gradeCode,
      grade: local.grade,
      policy: local.policy,
      credit: local.credit,
      fromServer: false,
      source: server?.source ?? 'NONE',
    }
  }
  const codes = [byAction?.code, byCredit?.code].filter((c): c is CreditGradeCode => !!c)
  const code = codes.reduce((worst, c) => (severity(c) > severity(worst) ? c : worst))
  const tier = GRADE_TIERS.find((t) => t.code === code)!
  return {
    gradeCode: tier.code,
    grade: tier.label,
    policy: byAction && byAction.code === code ? byAction.policy : tier.policy,
    credit: server.creditScore,
    fromServer: true,
    source: server.source,
  }
}
