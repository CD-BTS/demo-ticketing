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

  const applied = risk?.applied ?? null
  // 서버 정책이 등급을 정하는 경우(또는 서버 연동 중 조회 실패)에는 브라우저 산정의 정책 줄을 숨긴다.
  const serverDecides = !!risk && (!applied || applied.fromServer)

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
          <b className={applied ? `grade grade-${applied.gradeCode}` : ''}>
            {applied ? applied.grade : risk ? '확인 불가' : '—'}
          </b>
          {applied && applied.credit !== null && <span className="account-sub"> (신용 {applied.credit}점)</span>}
        </span>
        {applied?.fromServer ? (
          <span>적용 정책 <b>{applied.policy}</b></span>
        ) : (
          <span>의심 점수 <b>{applied && risk ? risk.dynamicPenalty : '—'}</b></span>
        )}
      </div>
      {risk && !applied && (
        <p className="small muted">서버에서 계정 정책을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.</p>
      )}
      {risk && (
        <details className="account-detail">
          <summary>{applied?.fromServer ? '브라우저 참고 산정 보기' : '산정 내역 보기'}</summary>
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
            {!serverDecides && <li><span>적용 정책 (penalty_policy)</span><b>{risk.policy}</b></li>}
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
          {serverDecides ? (
            <p className="small muted">
              브라우저에서 측정 가능한 지표만 반영한 참고값입니다. 위에 표시된 계정 등급과 적용 정책은 이 값이 아니라
              서버의 정책 결정을 따르며, 대기열 진입 시 같은 정책(CAPTCHA, 대기열 후순위)이 적용됩니다.
            </p>
          ) : (
            <p className="small muted">
              브라우저에서 측정 가능한 지표만 반영한 참고값입니다. AI 이상 점수, 카드·IP 집계, 관람·노쇼 이력은 서버 연동 후
              반영됩니다. 적용 정책(CAPTCHA, 대기열 후순위)은 정책 표시만 하며 아직 화면에 적용되지는 않습니다.
            </p>
          )}
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
