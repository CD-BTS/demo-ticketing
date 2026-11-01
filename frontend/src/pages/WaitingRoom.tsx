import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { queueApi, type QueueStatus } from '../api/queue'
import { ticketing } from '../api/ticketing'
import type { Concert } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useBehavior } from '../hooks/useBehavior'

const POLL_MS = 250 // 실제 서버 연동 시에는 서버 부담을 고려해 간격을 늘리거나 SSE/WebSocket 으로 교체
const ENTER_DELAY_MS = 10 // 번호가 0이 된 뒤 좌석 선택 화면으로 넘어가기까지 (0.01초)

interface Props {
  concertId: string
  onAdmit: () => void
}

export default function WaitingRoom({ concertId, onAdmit }: Props) {
  const { userId } = useAuth()
  const track = useBehavior()
  const nav = useNavigate()
  const [concert, setConcert] = useState<Concert | null>(null)
  const [status, setStatus] = useState<QueueStatus | null>(null)
  const [error, setError] = useState('')
  const ticketRef = useRef<string | null>(null)
  const admittedRef = useRef(false)

  useEffect(() => {
    if (!userId) return
    let alive = true
    let timer: ReturnType<typeof setInterval> | undefined

    ticketing.getConcert(concertId).then((c) => alive && setConcert(c)).catch(() => {})

    queueApi
      .join(concertId, userId)
      .then((t) => {
        if (!alive) return
        ticketRef.current = t.ticketId
        setStatus({ position: t.position, behind: t.behind })
        track('JOIN_QUEUE', { concertId, position: t.position })
        timer = setInterval(async () => {
          try {
            const s = await queueApi.status(t.ticketId)
            if (alive) setStatus(s)
          } catch {
            /* 일시적 오류는 다음 폴링에서 복구 */
          }
        }, POLL_MS)
      })
      .catch((e: Error) => alive && setError(e.message))

    return () => {
      alive = false
      if (timer) clearInterval(timer)
      // 입장하지 않고 화면을 떠나면 대기열에서 빠진다
      if (!admittedRef.current && ticketRef.current) queueApi.leave(ticketRef.current).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [concertId, userId])

  // 내 앞이 0명이 되면 0.01초 뒤 좌석 선택 화면으로
  useEffect(() => {
    if (status?.position !== 0 || admittedRef.current) return
    const t = setTimeout(() => {
      admittedRef.current = true
      onAdmit()
    }, ENTER_DELAY_MS)
    return () => clearTimeout(t)
  }, [status?.position, onAdmit])

  return (
    <div className="waiting">
      {concert && <p className="waiting-concert">{concert.title}</p>}
      <h1 className="waiting-title">대기 번호</h1>
      <p className="waiting-number" aria-live="polite">{status ? status.position : '–'}</p>
      <p className="waiting-behind">
        {status ? <>내 뒤에 <b>{status.behind.toLocaleString('ko-KR')}</b>명이 대기 중입니다</> : '대기열에 들어가는 중…'}
      </p>
      <p className="muted small">대기 번호가 0이 되면 자동으로 좌석 선택 화면으로 이동합니다. 이 화면을 닫지 마세요.</p>
      {error && <p className="error" role="alert">{error}</p>}
      <button className="btn btn-ghost" onClick={() => nav('/')}>대기 취소</button>
    </div>
  )
}
