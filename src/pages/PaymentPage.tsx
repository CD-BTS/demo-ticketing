import { useEffect, useMemo, useState, type ChangeEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ApiError, sleep } from '../api/client'
import { ticketing } from '../api/ticketing'
import type { Booking, Concert, Seat, SeatHold } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { dateTime, seatLabel, won } from '../components/format'
import { useBehavior } from '../hooks/useBehavior'

const CARD_DIGITS = 15 // 실제 카드번호 입력을 막기 위한 가상 번호 자리수
const PAY_DELAY_MS = 1200 // 결제 처리 중처럼 보이게 하는 mock 지연

// 15자리를 4-4-4-3 으로 끊어서 보여 준다
const formatCard = (digits: string) => [digits.slice(0, 4), digits.slice(4, 8), digits.slice(8, 12), digits.slice(12, 15)].filter(Boolean).join(' ')

export default function PaymentPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const nav = useNavigate()
  const { userId } = useAuth()
  const track = useBehavior()
  const hold = (location.state as { hold?: SeatHold } | null)?.hold

  const [concert, setConcert] = useState<Concert | null>(null)
  const [seats, setSeats] = useState<Seat[]>([])
  const [card, setCard] = useState('') // 숫자만. 어디에도 저장·전송하지 않고 화면 state 로만 쓴다.
  const [phase, setPhase] = useState<'form' | 'paying' | 'done'>('form')
  const [booking, setBooking] = useState<Booking | null>(null)
  const [error, setError] = useState('')
  const [leaving, setLeaving] = useState(false)
  const [remaining, setRemaining] = useState(() => (hold ? secondsLeft(hold.expiresAt) : 0))

  useEffect(() => {
    if (!userId || !hold) return
    Promise.all([ticketing.getConcert(id), ticketing.listSeats(id, userId)])
      .then(([c, list]) => {
        setConcert(c)
        setSeats(list)
        track('ENTER_PAYMENT', { concertId: id, seatIds: hold.seatIds })
      })
      .catch((e: Error) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, userId])

  // 선점 유지 시간 카운트다운 (결제 완료 후에는 멈춘다)
  useEffect(() => {
    if (!hold || phase === 'done') return
    const t = setInterval(() => setRemaining(secondsLeft(hold.expiresAt)), 1000)
    return () => clearInterval(t)
  }, [hold, phase])

  const mySeats = useMemo(() => seats.filter((s) => hold?.seatIds.includes(s.id)), [seats, hold])
  const total = mySeats.reduce((sum, s) => sum + s.price, 0)
  const expired = phase !== 'done' && remaining <= 0
  const ready = mySeats.length > 0 // 좌석·금액 정보가 로딩된 뒤에만 결제 가능

  if (!hold || !userId) return <Navigate to={`/concerts/${id}`} replace />

  const onCard = (e: ChangeEvent<HTMLInputElement>) => setCard(e.target.value.replace(/\D/g, '').slice(0, CARD_DIGITS))

  // '이전으로': 잡아 둔 좌석을 풀어 주고 좌석 선택 화면으로 돌아간다
  const goBack = async () => {
    setLeaving(true)
    track('BOOKING_CANCEL', { concertId: id, stage: 'PAYMENT', seatIds: hold!.seatIds })
    try {
      await ticketing.releaseHold(hold!.holdId, userId!)
    } catch {
      /* 해제에 실패해도 만료되면 풀리므로 화면 이동은 계속한다 */
    }
    nav(`/concerts/${id}`)
  }

  const pay = async () => {
    if (card.length !== CARD_DIGITS || expired || !ready) return
    setPhase('paying')
    setError('')
    try {
      await sleep(PAY_DELAY_MS) // TODO: 실제 결제(PG) 연동 시 이 자리에서 결제 요청. 카드번호는 서버로 직접 보내지 않는다.
      const b = await ticketing.confirmBooking(hold.holdId, userId)
      track('BOOKING_CONFIRM', { concertId: id, bookingId: b.id, seatIds: b.seatIds })
      setCard('')
      setBooking(b)
      setPhase('done')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '결제에 실패했습니다. 다시 시도해 주세요.')
      setPhase('form')
    }
  }

  // ---- 예매 완료 ----
  if (phase === 'done' && booking) {
    return (
      <div className="pay-wrap">
        <div className="card pay-card pay-done">
          <span className="badge badge-confirmed">결제 완료</span>
          <h1>예매가 완료되었습니다</h1>
          <p className="meta">{booking.concertTitle}</p>
          <p className="meta">{dateTime(booking.startAt)} · {booking.venue}</p>
          <p className="meta">좌석 {seatLabel(booking.seatIds)}</p>
          <p className="price">{won(booking.totalPrice)}</p>
          <p className="meta small">예매번호 {booking.id}</p>
          <div className="pay-actions">
            <button className="btn btn-primary" onClick={() => nav('/my', { state: { justBooked: booking.id } })}>
              예매 내역 보기
            </button>
            <Link className="btn btn-ghost" to="/">공연 목록으로</Link>
          </div>
        </div>
      </div>
    )
  }

  // ---- 결제 ----
  const mm = String(Math.floor(remaining / 60)).padStart(2, '0')
  const ss = String(remaining % 60).padStart(2, '0')
  const paying = phase === 'paying'

  return (
    <div className="pay-wrap">
      <h1 className="page-title">결제</h1>
      <div className="pay-grid">
        <section className="card pay-card">
          <h2>예매 정보</h2>
          {concert ? (
            <>
              <p className="pay-concert">{concert.title}</p>
              <p className="meta">{dateTime(concert.startAt)} · {concert.venue}</p>
            </>
          ) : (
            <p className="muted">불러오는 중…</p>
          )}
          <ul className="picked">
            {mySeats.map((s) => (
              <li key={s.id}>
                <span><b>{s.id}</b> · {s.grade}석</span>
                <span>{won(s.price)}</span>
              </li>
            ))}
          </ul>
          <div className="total">
            <span>결제 금액</span>
            <b>{won(total)}</b>
          </div>
        </section>

        <section className="card pay-card">
          <h2>카드 결제</h2>
          <div className="timer" role="status">
            {expired ? '선점 시간이 만료되었습니다' : <>좌석 선점 유지 시간 <b>{mm}:{ss}</b></>}
          </div>

          <label className="field">
            <span>카드번호 (테스트용 {CARD_DIGITS}자리)</span>
            <input
              name="test-card-number"
              value={formatCard(card)}
              onChange={onCard}
              inputMode="numeric"
              autoComplete="off"
              placeholder="0000 0000 0000 000"
              disabled={paying || expired}
              aria-describedby="card-help"
            />
          </label>
          <p id="card-help" className="small muted">
            실제 카드번호는 입력하지 마세요. 임의의 숫자 {CARD_DIGITS}자리면 결제됩니다. ({card.length}/{CARD_DIGITS})
          </p>

          {error && <p className="error" role="alert">{error}</p>}

          <button className="btn btn-primary btn-block" disabled={card.length !== CARD_DIGITS || paying || expired || !ready} onClick={pay}>
            {paying ? '결제 처리 중…' : `${won(total)} 결제하기`}
          </button>
          <button
            className="btn btn-ghost btn-block"
            disabled={paying || leaving}
            onClick={goBack}
          >
            {expired ? '좌석 다시 선택' : '이전으로 (좌석 다시 선택)'}
          </button>
        </section>
      </div>
    </div>
  )
}

function secondsLeft(expiresAt: string) {
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000))
}
