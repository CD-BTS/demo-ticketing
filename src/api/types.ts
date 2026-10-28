// ---- 공통 응답 envelope (API 명세서 2. 공통 응답 구조) ----
export interface ApiEnvelope<T> {
  success: boolean
  code: string
  message: string
  data: T
}

// ---- BTS API ----
export interface BtsUser {
  userId: string
  // 명세서에 응답 필드가 없어 최소 필드만 정의. 백엔드 확정 후 보완.
  [key: string]: unknown
}

/**
 * POST /api/v1/events 요청 body.
 * ⚠️ 명세서에 필드 정의가 없음 → 서연(BE)에게 확인 필요. 아래는 가정.
 */
export type BehaviorEventType =
  | 'VIEW_CONCERT'
  | 'VIEW_SEATS'
  | 'SELECT_SEAT'
  | 'HOLD_SEAT'
  | 'HOLD_FAILED'
  | 'BOOKING_CONFIRM'
  | 'BOOKING_CANCEL'

export interface BehaviorEventPayload {
  userId: string
  eventType: BehaviorEventType
  occurredAt: string // ISO 8601
  deviceId: string
  metadata?: Record<string, unknown>
}

// ---- 티켓팅 서비스 (명세서에 없음 → 서연 2차에서 정의 예정, 현재는 mock) ----
export interface Concert {
  id: string
  title: string
  artist: string
  venue: string
  startAt: string
  priceFrom: number
  tagline: string
  hue: number // 포스터 색상용
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED'
export type SeatGrade = 'VIP' | 'R' | 'S'

export interface Seat {
  id: string // e.g. "A-05"
  row: string
  number: number
  grade: SeatGrade
  price: number
  status: SeatStatus
  heldByMe?: boolean
}

export interface SeatHold {
  holdId: string
  concertId: string
  seatIds: string[]
  expiresAt: string
}

export type BookingStatus = 'CONFIRMED' | 'CANCELLED'

export interface Booking {
  id: string
  concertId: string
  concertTitle: string
  venue: string
  startAt: string
  seatIds: string[]
  totalPrice: number
  status: BookingStatus
  createdAt: string
}

// ---- 입장용 QR ----
/**
 * 예매 건별 입장 QR 데이터. 백엔드(인영) QR API 확정 후 필드 보완.
 * 지금은 정적 QR이지만 추후 동적 QR(15초마다 갱신)로 바뀔 예정:
 *  - token: QR에 담길 문자열(검표 서버가 검증)
 *  - refreshIntervalSec: 있으면 그 주기(15)로 다시 받아와 QR 교체
 */
export interface TicketQr {
  token: string
  expiresAt?: string
  refreshIntervalSec?: number
}

// ---- 계정 등급 / 의심 점수 (산식: src/risk/credit.ts) ----
export type { CreditResult as AccountRisk } from '../risk/credit'
