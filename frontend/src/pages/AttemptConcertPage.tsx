import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { attemptsEnabled, clearAttempt, getAttempt, reportStage, startAttempt, type Attempt } from '../api/bookingAttempts'
import { ticketing } from '../api/ticketing'
import { ApiError } from '../api/client'
import type { Concert } from '../api/types'
import ConcertDetailPage from './ConcertDetailPage'
import AttemptWaitingRoom from './AttemptWaitingRoom'

function Gate({ concertId }: { concertId: string }) {
  const { userId } = useAuth()
  const nav = useNavigate()
  const [concert, setConcert] = useState<Concert | null>(null)
  const [attempt, setAttempt] = useState<Attempt | null>(null)
  const [admitted, setAdmitted] = useState(false)
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef(false)
  useEffect(() => {
    let alive = true
    ticketing.getConcert(concertId).then(c => { if (alive) setConcert(c) }).catch(() => {})
    if (userId) attemptsEnabled().then(async active => {
      if (!alive) return null
      setEnabled(active)
      if (!active) { clearAttempt(userId, concertId);return null }
      return getAttempt(userId, concertId)
    }).then(a => {
      if (!alive) return
      if (a && !['ABANDONED', 'EXPIRED', 'FAILED'].includes(a.status) && a.stage !== 'COMPLETED') {
        setAttempt(a)
        if (a.holdId) nav(`/concerts/${concertId}/payment`, { replace: true })
      } else if (a) clearAttempt(userId, concertId)
    }).catch(e => { if (alive) setError(e instanceof ApiError && e.status === 404 ? '예매하기를 눌러 이전 시작 요청을 다시 확인해 주세요.' : '진행 상태를 확인하지 못했습니다. 다시 시도해 주세요.') })
      .finally(() => { if (alive) setReady(true) })
    return () => { alive = false }
  }, [concertId, userId, nav])
  const begin = async () => {
    if (!userId || pending.current) return
    pending.current = true; setError('')
    try {
      const active = await attemptsEnabled();setEnabled(active)
      if (active) {
        await startAttempt(userId, concertId)
        const current = await getAttempt(userId, concertId)
        if (current?.holdId) nav(`/concerts/${concertId}/payment`, { replace: true })
        else setAttempt(current)
      }
    }
    catch (e) { setError(e instanceof Error ? e.message : '시작 요청을 확인하지 못했습니다. 같은 요청으로 다시 시도해 주세요.') }
    finally { pending.current = false }
  }
  if (enabled === false) return admitted ? <ConcertDetailPage /> : <AttemptWaitingRoom concertId={concertId} onAdmit={() => setAdmitted(true)} />
  if (!attempt) return <section className="card">
    <h1>{concert?.title ?? '공연 상세'}</h1>
    <p>{concert?.venue}</p><p>예매하기를 누르면 필요한 인증과 대기열을 거쳐 좌석을 선택합니다.</p>
    {error && <p className="error" role="alert">{error}</p>}
    <button className="btn btn-primary" disabled={!ready} onClick={begin}>예매하기</button>
  </section>
  return admitted ? <ConcertDetailPage attemptId={attempt.attemptId} /> : <AttemptWaitingRoom concertId={concertId} attemptId={attempt.attemptId} onAdmit={async () => {
    if (userId) await reportStage(userId, concertId, 'SEAT_SELECTION')
    setAdmitted(true)
  }} />
}
export default function AttemptConcertPage() {
  const { id = '' } = useParams()
  return <Gate key={id} concertId={id} />
}
