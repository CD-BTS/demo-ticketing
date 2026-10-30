import { useCallback, useState } from 'react'
import { useParams } from 'react-router-dom'
import { isAdmitted, markAdmitted } from '../api/queue'
import { useAuth } from '../auth/AuthContext'
import ConcertDetailPage from './ConcertDetailPage'
import WaitingRoom from './WaitingRoom'

/** 공연 입장: 대기열을 통과하기 전에는 대기 화면, 통과 후에는 좌석 선택 화면 */
function Gate({ concertId }: { concertId: string }) {
  const { userId } = useAuth()
  const [admitted, setAdmitted] = useState(() => (userId ? isAdmitted(userId, concertId) : false))

  const onAdmit = useCallback(() => {
    if (userId) markAdmitted(userId, concertId)
    setAdmitted(true)
  }, [userId, concertId])

  return admitted ? <ConcertDetailPage /> : <WaitingRoom concertId={concertId} onAdmit={onAdmit} />
}

export default function ConcertPage() {
  const { id = '' } = useParams()
  return <Gate key={id} concertId={id} />
}
