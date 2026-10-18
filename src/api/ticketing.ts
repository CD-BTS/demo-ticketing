/**
 * 티켓팅 서비스 API (공연 · 좌석 · 선점 · 예매)
 *
 * 아직 백엔드 명세가 없어서 localStorage 기반 mock으로 동작한다.
 * 서연(BE)의 API가 나오면 `real` 객체의 TODO 엔드포인트만 채우고
 * VITE_USE_MOCK_TICKETING=false 로 바꾸면 화면 코드는 그대로 쓸 수 있다.
 */
import { ApiError, request, sleep, USE_MOCK_TICKETING } from './client'
import type { Booking, Concert, Seat, SeatGrade, SeatHold } from './types'

const HOLD_MS = 5 * 60 * 1000 // 선점 유지 시간 (가정: 5분)
const ROWS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']
const SEATS_PER_ROW = 12

export interface TicketingApi {
  listConcerts(): Promise<Concert[]>
  getConcert(id: string): Promise<Concert>
  listSeats(concertId: string, userId: string): Promise<Seat[]>
  holdSeats(concertId: string, userId: string, seatIds: string[]): Promise<SeatHold>
  confirmBooking(holdId: string, userId: string): Promise<Booking>
  cancelBooking(bookingId: string, userId: string): Promise<Booking>
  listMyBookings(userId: string): Promise<Booking[]>
}

// ------------------------------------------------------------------
// Mock 데이터
// ------------------------------------------------------------------
const CONCERTS: Concert[] = [
  {
    id: 'c1',
    title: 'MIDNIGHT SIGNAL — Seoul Encore',
    artist: 'Nova Lane',
    venue: '올림픽공원 KSPO DOME',
    startAt: '2026-12-12T18:00:00+09:00',
    priceFrom: 99000,
    tagline: '밤 사이로 번지는 신호, 앙코르 서울',
    hue: 265,
  },
  {
    id: 'c2',
    title: 'Paper Lanterns Tour 2026',
    artist: 'The Hollow Birds',
    venue: '인스파이어 아레나',
    startAt: '2026-12-26T19:00:00+09:00',
    priceFrom: 88000,
    tagline: '종이 등불 아래서 듣는 어쿠스틱 세트',
    hue: 28,
  },
  {
    id: 'c3',
    title: 'Neon Harbor Live',
    artist: 'Kite & Ember',
    venue: '고척스카이돔',
    startAt: '2027-01-09T17:30:00+09:00',
    priceFrom: 110000,
    tagline: '항구의 네온, 한 번뿐인 라이브',
    hue: 190,
  },
]

const gradeOf = (row: string): SeatGrade =>
  row <= 'B' ? 'VIP' : row <= 'E' ? 'R' : 'S'
const PRICE: Record<SeatGrade, number> = { VIP: 154000, R: 132000, S: 99000 }

// 결정적 의사난수: 공연마다 "이미 팔린 좌석"이 항상 같게
function seeded(seed: string) {
  let h = 2166136261
  for (const ch of seed) h = Math.imul(h ^ ch.charCodeAt(0), 16777619)
  return () => {
    h = Math.imul(h ^ (h >>> 15), 2246822519)
    h = Math.imul(h ^ (h >>> 13), 3266489917)
    return ((h ^= h >>> 16) >>> 0) / 4294967296
  }
}

type StoredHold = SeatHold & { userId: string }
interface Stored {
  booked: Record<string, string[]> // concertId -> 예매 확정된 seatIds
  holds: StoredHold[]
  bookings: (Booking & { userId: string })[]
}

const KEY = 'fds.mock.ticketing'
function load(): Stored {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) return JSON.parse(raw) as Stored
  } catch {
    /* ignore */
  }
  return { booked: {}, holds: [], bookings: [] }
}
function save(s: Stored) {
  try {
    localStorage.setItem(KEY, JSON.stringify(s))
  } catch {
    /* ignore */
  }
}
// 만료된 선점 정리 — 만료 시 좌석은 다시 AVAILABLE (상태 복구)
function purge(s: Stored) {
  const now = Date.now()
  s.holds = s.holds.filter((h) => new Date(h.expiresAt).getTime() > now)
}

const soldOut = (concertId: string): Set<string> => {
  const rnd = seeded(concertId)
  const set = new Set<string>()
  for (const row of ROWS)
    for (let n = 1; n <= SEATS_PER_ROW; n++) if (rnd() < 0.28) set.add(`${row}-${String(n).padStart(2, '0')}`)
  return set
}

const mock: TicketingApi = {
  async listConcerts() {
    await sleep(250)
    return CONCERTS
  },
  async getConcert(id) {
    await sleep(150)
    const c = CONCERTS.find((x) => x.id === id)
    if (!c) throw new ApiError('CONCERT_NOT_FOUND', '공연을 찾을 수 없습니다.', 404)
    return c
  },
  async listSeats(concertId, userId) {
    await sleep(250)
    const s = load()
    purge(s)
    save(s)
    const base = soldOut(concertId)
    const booked = new Set([...base, ...(s.booked[concertId] ?? [])])
    const heldByOthers = new Set<string>()
    const heldByMe = new Set<string>()
    for (const h of s.holds) {
      if (h.concertId !== concertId) continue
      for (const id of h.seatIds) (h.userId === userId ? heldByMe : heldByOthers).add(id)
    }
    const seats: Seat[] = []
    for (const row of ROWS)
      for (let n = 1; n <= SEATS_PER_ROW; n++) {
        const id = `${row}-${String(n).padStart(2, '0')}`
        const grade = gradeOf(row)
        const status = booked.has(id) ? 'BOOKED' : heldByOthers.has(id) || heldByMe.has(id) ? 'HELD' : 'AVAILABLE'
        seats.push({ id, row, number: n, grade, price: PRICE[grade], status, heldByMe: heldByMe.has(id) })
      }
    return seats
  },
  async holdSeats(concertId, userId, seatIds) {
    await sleep(300)
    const s = load()
    purge(s)
    const base = soldOut(concertId)
    const booked = new Set([...base, ...(s.booked[concertId] ?? [])])
    const held = new Set(s.holds.filter((h) => h.concertId === concertId).flatMap((h) => h.seatIds))
    const conflict = seatIds.filter((id) => booked.has(id) || held.has(id))
    if (conflict.length) {
      save(s)
      throw new ApiError('SEAT_ALREADY_TAKEN', `이미 선택된 좌석입니다: ${conflict.join(', ')}`, 409)
    }
    // 같은 사용자의 기존 선점은 교체
    s.holds = s.holds.filter((h) => !(h.userId === userId && h.concertId === concertId))
    const hold: StoredHold = {
      holdId: `h_${Date.now().toString(36)}`,
      concertId,
      seatIds,
      expiresAt: new Date(Date.now() + HOLD_MS).toISOString(),
      userId,
    }
    s.holds.push(hold)
    save(s)
    return hold
  },
  async confirmBooking(holdId, userId) {
    await sleep(400)
    const s = load()
    purge(s)
    const hold = s.holds.find((h) => h.holdId === holdId && h.userId === userId)
    if (!hold) {
      save(s)
      throw new ApiError('HOLD_EXPIRED', '좌석 선점 시간이 만료되었습니다. 다시 선택해 주세요.', 410)
    }
    const concert = CONCERTS.find((c) => c.id === hold.concertId)!
    const seats = await mock.listSeats(hold.concertId, userId)
    const total = hold.seatIds.reduce((sum, id) => sum + (seats.find((x) => x.id === id)?.price ?? 0), 0)
    const s2 = load()
    s2.holds = s2.holds.filter((h) => h.holdId !== holdId)
    s2.booked[hold.concertId] = [...(s2.booked[hold.concertId] ?? []), ...hold.seatIds]
    const booking = {
      id: `b_${Date.now().toString(36)}`,
      concertId: concert.id,
      concertTitle: concert.title,
      venue: concert.venue,
      startAt: concert.startAt,
      seatIds: hold.seatIds,
      totalPrice: total,
      status: 'CONFIRMED' as const,
      createdAt: new Date().toISOString(),
      userId,
    }
    s2.bookings.unshift(booking)
    save(s2)
    return booking
  },
  async cancelBooking(bookingId, userId) {
    await sleep(300)
    const s = load()
    const b = s.bookings.find((x) => x.id === bookingId && x.userId === userId)
    if (!b) throw new ApiError('BOOKING_NOT_FOUND', '예매 내역을 찾을 수 없습니다.', 404)
    if (b.status === 'CANCELLED') return b
    b.status = 'CANCELLED'
    // 좌석 복구
    s.booked[b.concertId] = (s.booked[b.concertId] ?? []).filter((id) => !b.seatIds.includes(id))
    save(s)
    return b
  },
  async listMyBookings(userId) {
    await sleep(200)
    return load().bookings.filter((b) => b.userId === userId)
  },
}

// ------------------------------------------------------------------
// 실제 API (TODO: 서연 명세 확정 후 엔드포인트 채우기)
// ------------------------------------------------------------------
const real: TicketingApi = {
  listConcerts: () => request('GET', '/concerts'),
  getConcert: (id) => request('GET', `/concerts/${id}`),
  listSeats: (concertId) => request('GET', `/concerts/${concertId}/seats`),
  holdSeats: (concertId, _u, seatIds) => request('POST', `/concerts/${concertId}/seat-holds`, { seatIds }),
  confirmBooking: (holdId) => request('POST', '/bookings', { holdId }),
  cancelBooking: (bookingId) => request('POST', `/bookings/${bookingId}/cancel`),
  listMyBookings: () => request('GET', '/bookings/me'),
}

export const ticketing: TicketingApi = USE_MOCK_TICKETING ? mock : real
