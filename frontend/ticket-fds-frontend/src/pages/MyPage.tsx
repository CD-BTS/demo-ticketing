import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ApiError } from '../api/client'
import { getAccountRisk } from '../api/bts'
import { ticketing } from '../api/ticketing'
import type { AccountRisk, Booking } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { dateTime, won } from '../components/format'
import CardManager from '../components/CardManager'
import QrModal from '../components/QrModal'
import { useBehavior } from '../hooks/useBehavior'
import { FEATURE_SPECS, formatFeature } from '../risk/features'

export default function MyPage() {
  const { userId } = useAuth()
  const track = useBehavior()
  const location = useLocation()
  const justBooked = (location.state as { justBooked?: string } | null)?.justBooked

  const [bookings, setBookings] = useState<Booking[] | null>(null)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  const [qrBooking, setQrBooking] = useState<Booking | null>(null)
  const [risk, setRisk] = useState<AccountRisk | null>(null)

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

  useEffect(() => {
    if (!userId) return
    getAccountRisk(userId)
      .then(setRisk)
      .catch(() => setRisk(null))
  }, [userId, bookings]) // 예매/취소 후에도 다시 계산

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
      <div className="account">
        <span>
          계정 등급{' '}
          <b className={risk ? `grade grade-${risk.gradeCode}` : ''}>{risk ? risk.grade : '—'}</b>
          {risk && <span className="account-sub"> (신용 {risk.credit}점)</span>}
        </span>
        <span>의심 점수 <b>{risk ? risk.dynamicPenalty : '—'}</b></span>
      </div>
      {risk && (
        <details className="account-detail">
          <summary>산정 내역 보기</summary>
          <ul>
            <li><span>기본 신뢰도 (base_credit)</span><b>{risk.baseCredit}</b></li>
            {risk.penalties.map((p) => (
              <li key={p.key} className={p.points ? 'is-hit' : ''}>
                <span>{p.label}</span>
                <b>{p.points ? `−${p.points}` : '0'}</b>
              </li>
            ))}
            <li className="sum"><span>감점 합계 (dynamic_penalty = 의심 점수)</span><b>{risk.dynamicPenalty}</b></li>
            <li className="sum"><span>신용 점수 (credit_score)</span><b>{risk.credit}</b></li>
            <li><span>적용 정책 (penalty_policy)</span><b>{risk.policy}</b></li>
          </ul>

          <h3 className="feature-title">행동 지표 (Feature)</h3>
          <ul className="features">
            {FEATURE_SPECS.map((f) => (
              <li key={f.key} title={`${f.definition} · 기준 후보 ${f.candidates}`}>
                <span>
                  {f.label} <code>{f.key}</code>
                </span>
                <b className={f.source === 'server' ? 'is-server' : ''}>{formatFeature(risk.features, f.key)}</b>
              </li>
            ))}
          </ul>
          <p className="small muted">
            브라우저에서 측정 가능한 지표만 반영한 참고값입니다. AI 이상 점수, 카드·IP 집계, 관람·노쇼 이력은 서버 연동 후
            반영됩니다. 적용 정책(CAPTCHA, 대기열 후순위)은 정책 표시만 하며 아직 화면에 적용되지는 않습니다.
          </p>
        </details>
      )}
      {justBooked && <p className="notice">예매가 완료되었습니다.</p>}
      {error && <p className="error" role="alert">{error}</p>}

      {userId && <CardManager userId={userId} />}

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
                <div className="booking-actions">
                  <button className="btn btn-primary btn-sm" onClick={() => setQrBooking(b)}>QR 보기</button>
                  <button className="btn btn-ghost btn-sm" disabled={busyId === b.id} onClick={() => cancel(b)}>
                    {busyId === b.id ? '취소 중…' : '예매 취소'}
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {qrBooking && userId && <QrModal booking={qrBooking} userId={userId} onClose={() => setQrBooking(null)} />}
    </>
  )
}
