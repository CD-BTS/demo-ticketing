import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { queueApi, type QueueStatus, captchaChallenge, captchaVerify, type CaptchaChallenge } from '../api/queue'
import { ApiError } from '../api/client'
import { captchaTransition, clearAttempt, reportStage, retryTransient } from '../api/bookingAttempts'
import { useAuth } from '../auth/AuthContext'

interface Props { concertId: string; attemptId?: string; onAdmit: () => void | Promise<void> }
export default function AttemptWaitingRoom({ concertId, attemptId, onAdmit }: Props) {
  const { userId } = useAuth()
  const nav = useNavigate()
  const [status, setStatus] = useState<QueueStatus | null>(null)
  const [challenge, setChallenge] = useState<CaptchaChallenge | null>(null)
  const [answer, setAnswer] = useState('')
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  const [token, setToken] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)
  const verificationLock = useRef(false)
  const onAdmitRef = useRef(onAdmit); onAdmitRef.current = onAdmit
  useEffect(() => {
    if (!userId) return
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    let ticketId = concertId
    const poll = async () => {
      try {
        const s = await queueApi.status(ticketId, attemptId)
        if (!alive) return
        setStatus(s)
        if (s.status === 'ADMITTED' || s.position === 0) { await onAdmitRef.current(); return }
        if (s.status === 'EXPIRED' || s.status === 'NOT_JOINED') { setError('대기열 입장이 만료되었습니다. 대기열에 다시 등록해 주세요.'); return }
        timer = setTimeout(poll, 1000)
      } catch (e) { if (alive) { setError(e instanceof Error ? e.message : '단계 기록을 다시 시도하고 있습니다.');timer = setTimeout(poll, 2000) } }
    }
    queueApi.join(concertId, userId, attemptId, token).then(s => {
      if (!alive) return
      ticketId = s.ticketId;setChallenge(null);setStatus(s);setError('');poll()
    }).catch(async e => {
      if (!alive) return
      if (e instanceof ApiError && (e.code === 'CAPTCHA_REQUIRED' || e.code === 'CAPTCHA_INVALID')) {
        try { const c = await captchaChallenge(concertId, attemptId); if (alive) { setChallenge(c);setAnswer('') } }
        catch (failure) { if (alive) setError((failure as Error).message) }
      } else setError(e instanceof Error ? e.message : '대기열 요청에 실패했습니다.')
    })
    return () => { alive = false; clearTimeout(timer) }
  }, [concertId, userId, attemptId, token, retry])
  const verify = async () => {
    if (!challenge || !userId || verificationLock.current) return
    verificationLock.current = true
    setBusy(true);setError('')
    try {
      const transition = await captchaTransition(userId, concertId, challenge.challengeId, answer)
      const grant = await retryTransient(() => captchaVerify(concertId, attemptId, challenge.challengeId, answer, transition))
      setToken(grant.captchaToken);setChallenge(null)
    }
    catch (e) { setError((e as Error).message) }
    finally { setBusy(false);verificationLock.current = false }
  }
  const leave = async () => {
    if (!userId || busy) return
    setBusy(true)
    try {
      await queueApi.leave(concertId)
      const stage = challenge ? 'CAPTCHA' : status ? 'QUEUE' : 'STARTED'
      if (attemptId) await reportStage(userId, concertId, stage, 'ABANDONED')
      clearAttempt(userId, concertId);nav('/')
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  return <section className="card waiting">
    <h1>{challenge ? '보안 확인' : '입장 대기'}</h1>
    {challenge ? <form onSubmit={e => { e.preventDefault();verify() }}>
      <p>{challenge.question}</p><input aria-label="보안 확인 답" value={answer} onChange={e => setAnswer(e.target.value)} />
      <button className="btn btn-primary" disabled={busy || !answer}>확인</button>
      <button type="button" className="btn btn-ghost" onClick={() => setRetry(n => n + 1)}>새 문제</button>
    </form> : <p>{status?.status === 'WAITING' ? `현재 대기 순번 ${status.position}` : '입장 상태를 확인하고 있습니다.'}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {!challenge && error && <button className="btn btn-ghost" disabled={busy} onClick={() => setRetry(n => n + 1)}>다시 확인</button>}
    <button className="btn btn-ghost" disabled={busy} onClick={leave}>예매 시도 취소</button>
  </section>
}
