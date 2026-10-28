import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { ticketing } from '../api/ticketing'
import type { Concert, Seat, SeatHold } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { dateTime, won } from '../components/format'
import SeatMap from '../components/SeatMap'
import { useBehavior } from '../hooks/useBehavior'
import { recordSeatClick } from '../risk/signals'

const MAX_SEATS = 4

export default function ConcertDetailPage() {
  const { id = '' } = useParams()
  const { userId } = useAuth()
  const track = useBehavior()
  const nav = useNavigate()

  const [concert, setConcert] = useState<Concert | null>(null)
  const [seats, setSeats] = useState<Seat[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [hold, setHold] = useState<SeatHold | null>(null)
  const [remaining, setRemaining] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  // "예매 소요 시간" feature 후보: 좌석 화면 진입 시각 기준 경과 시간을 이벤트에 실어 보낸다
  const enteredAt = useRef(Date.now())
  const firstSelectDone = useRef(false)
  const elapsed = () => Date.now() - enteredAt.current

  const loadSeats = useCallback(async () => {
    if (!userId) return
    const list = await ticketing.listSeats(id, userId)
    setSeats(list)
    return list
  }, [id, userId])

  useEffect(() => {
    if (!userId) return
    enteredAt.current = Date.now()
    firstSelectDone.current = false
    Promise.all([ticketing.getConcert(id), loadSeats()])
      .then(([c]) => {
        enteredAt.current = Date.now()
        setConcert(c)
        track('VIEW_CONCERT', { concertId: id })
        track('VIEW_SEATS', { concertId: id })
      })
      .catch((e: Error) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, userId])

  // 선점 만료 카운트다운
  useEffect(() => {
    if (!hold) return
    const tick = () => {
      const left = Math.max(0, Math.floor((new Date(hold.expiresAt).getTime() - Date.now()) / 1000))
      setRemaining(left)
      if (left === 0) {
        setHold(null)
        setSelected([])
        setNotice('선점 시간이 만료되어 좌석이 반환되었습니다. 다시 선택해 주세요.')
        loadSeats()
      }
    }
    tick()
    const t = setInterval(tick, 1000)
    return () => clearInterval(t)
  }, [hold, loadSeats])

  const selectedSeats = useMemo(() => seats.filter((s) => selected.includes(s.id)), [seats, selected])
  const total = selectedSeats.reduce((sum, s) => sum + s.price, 0)

  const toggle = (seat: Seat) => {
    setError('')
    setNotice('')
    setSelected((prev) => {
      if (prev.includes(seat.id)) return prev.filter((x) => x !== seat.id)
      if (prev.length >= MAX_SEATS) {
        setError(`좌석은 최대 ${MAX_SEATS}석까지 선택할 수 있습니다.`)
        return prev
      }
      track('SELECT_SEAT', { concertId: id, seatId: seat.id, elapsedMs: elapsed() })
      if (userId) {
        const first = !firstSelectDone.current
        firstSelectDone.current = true
        recordSeatClick(userId, Date.now(), first ? elapsed() / 1000 : undefined)
      }
      return [...prev, seat.id]
    })
  }

  const doHold = async () => {
    if (!userId || !selected.length) return
    setBusy(true)
    setError('')
    try {
      const h = await ticketing.holdSeats(id, userId, selected)
      setHold(h)
      track('HOLD_SEAT', { concertId: id, seatIds: selected, elapsedMs: elapsed() })
      await loadSeats()
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : '좌석 선점에 실패했습니다.'
      setError(msg)
      track('HOLD_FAILED', { concertId: id, seatIds: selected, code: e instanceof ApiError ? e.code : 'UNKNOWN' })
      setSelected([])
      await loadSeats()
    } finally {
      setBusy(false)
    }
  }

  const doConfirm = async () => {
    if (!userId || !hold) return
    setBusy(true)
    setError('')
    try {
      const b = await ticketing.confirmBooking(hold.holdId, userId)
      track('BOOKING_CONFIRM', { concertId: id, bookingId: b.id, seatIds: b.seatIds, elapsedMs: elapsed() })
      nav('/my', { state: { justBooked: b.id } })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '예매 확정에 실패했습니다.')
      setHold(null)
      setSelected([])
      await loadSeats()
    } finally {
      setBusy(false)
    }
  }

  const mm = String(Math.floor(remaining / 60)).padStart(2, '0')
  const ss = String(remaining % 60).padStart(2, '0')

  if (error && !concert) return <p className="error">{error}</p>
  if (!concert) return <p className="muted">불러오는 중…</p>

  return (
    <div className="detail">
      <section className="detail-main">
        <div
          className="detail-hero"
          style={{ background: `hsl(${concert.hue} 60% 38%)` }}
        >
          <span className="poster-artist">{concert.artist}</span>
          <h1>{concert.title}</h1>
          <p>{dateTime(concert.startAt)} · {concert.venue}</p>
        </div>
        <SeatMap seats={seats} selected={selected} onToggle={toggle} disabled={busy} />
      </section>

      <aside className="panel">
        <h2>선택한 좌석</h2>
        {selectedSeats.length === 0 ? (
          <p className="muted">좌석을 선택해 주세요. (최대 {MAX_SEATS}석)</p>
        ) : (
          <ul className="picked">
            {selectedSeats.map((s) => (
              <li key={s.id}>
                <span><b>{s.id}</b> · {s.grade}석</span>
                <span>{won(s.price)}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="total">
          <span>합계</span>
          <b>{won(total)}</b>
        </div>

        {hold && (
          <div className="timer" role="status">
            선점 유지 시간 <b>{mm}:{ss}</b>
          </div>
        )}
        {notice && <p className="notice">{notice}</p>}
        {error && <p className="error" role="alert">{error}</p>}

        {!hold ? (
          <button className="btn btn-primary btn-block" disabled={!selected.length || busy} onClick={doHold}>
            {busy ? '처리 중…' : '좌석 선점하기'}
          </button>
        ) : (
          <>
            <button className="btn btn-primary btn-block" disabled={busy} onClick={doConfirm}>
              {busy ? '처리 중…' : '예매 확정하기'}
            </button>
            <button
              className="btn btn-ghost btn-block"
              disabled={busy}
              onClick={() => {
                setHold(null)
                track('BOOKING_CANCEL', { concertId: id, stage: 'HOLD', seatIds: hold.seatIds })
              }}
            >
              좌석 다시 선택
            </button>
          </>
        )}
      </aside>
    </div>
  )
}
