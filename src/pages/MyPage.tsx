import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ApiError } from '../api/client'
import { ticketing } from '../api/ticketing'
import type { Booking } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { dateTime, won } from '../components/format'
import { useBehavior } from '../hooks/useBehavior'

export default function MyPage() {
  const { userId } = useAuth()
  const track = useBehavior()
  const location = useLocation()
  const justBooked = (location.state as { justBooked?: string } | null)?.justBooked

  const [bookings, setBookings] = useState<Booking[] | null>(null)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!userId) return
    try {
      setBookings(await ticketing.listMyBookings(userId))
    } catch (e) {
      setError((e as Error).message)
    }
  }, [userId])

  useEffect(() => {
    load()
  }, [load])

  const cancel = async (b: Booking) => {
    if (!userId || !window.confirm(`'${b.concertTitle}' 예매를 취소할까요?`)) return
    setBusyId(b.id)
    setError('')
    try {
      await ticketing.cancelBooking(b.id, userId)
      track('BOOKING_CANCEL', { bookingId: b.id, concertId: b.concertId, stage: 'CONFIRMED' })
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '취소에 실패했습니다.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <h1 className="page-title">마이페이지</h1>
      <p className="muted">사용자 ID: <b>{userId}</b></p>
      {justBooked && <p className="notice">예매가 완료되었습니다.</p>}
      {error && <p className="error" role="alert">{error}</p>}

      <h2 className="section-title">예매 내역</h2>
      {!bookings ? (
        <p className="muted">불러오는 중…</p>
      ) : bookings.length === 0 ? (
        <div className="empty">
          <p>아직 예매 내역이 없어요.</p>
          <Link className="btn btn-primary" to="/">공연 보러 가기</Link>
        </div>
      ) : (
        <ul className="bookings">
          {bookings.map((b) => (
            <li key={b.id} className={`card booking ${b.status === 'CANCELLED' ? 'is-cancelled' : ''} ${b.id === justBooked ? 'is-new' : ''}`}>
              <div>
                <span className={`badge badge-${b.status.toLowerCase()}`}>
                  {b.status === 'CONFIRMED' ? '예매 완료' : '취소됨'}
                </span>
                <h3>{b.concertTitle}</h3>
                <p className="meta">{dateTime(b.startAt)} · {b.venue}</p>
                <p className="meta">좌석 {b.seatIds.join(', ')} · {won(b.totalPrice)}</p>
                <p className="meta small">예매번호 {b.id}</p>
              </div>
              {b.status === 'CONFIRMED' && (
                <button className="btn btn-ghost btn-sm" disabled={busyId === b.id} onClick={() => cancel(b)}>
                  {busyId === b.id ? '취소 중…' : '예매 취소'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
