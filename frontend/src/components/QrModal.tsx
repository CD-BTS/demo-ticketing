import { QRCodeSVG } from 'qrcode.react'
import { useEffect, useState } from 'react'
import { ticketing } from '../api/ticketing'
import type { Booking, TicketQr } from '../api/types'
import { seatLabel } from './format'

interface Props {
  booking: Booking
  userId: string
  onClose: () => void
}

type State = { status: 'loading' } | { status: 'empty' } | { status: 'error'; message: string } | { status: 'ready'; qr: TicketQr }

/**
 * 입장용 QR 모달.
 * - QR 데이터를 받으면 QR 이미지를 보여주고, 아직 없으면 "발급 전" 안내를 보여준다.
 * - TODO(동적 QR): qr.refreshIntervalSec(15초)가 오면 그 주기로 getTicketQr 를 다시 호출해
 *   state 의 qr 만 교체하면 된다. (QRCodeSVG 는 value 가 바뀌면 자동으로 다시 그려진다)
 */
export default function QrModal({ booking, userId, onClose }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let alive = true
    ticketing
      .getTicketQr(booking.id, userId)
      .then((qr) => alive && setState(qr ? { status: 'ready', qr } : { status: 'empty' }))
      .catch((e: Error) => alive && setState({ status: 'error', message: e.message }))
    return () => {
      alive = false
    }
  }, [booking.id, userId])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" aria-label="입장 QR" onClick={(e) => e.stopPropagation()}>
        <h2>입장 QR</h2>
        <p className="meta">{booking.concertTitle}</p>
        <p className="meta">좌석 {seatLabel(booking.seatIds)}</p>

        <div className="qr-box">
          {state.status === 'loading' && <p className="muted">QR을 불러오는 중…</p>}
          {state.status === 'empty' && (
            <p className="muted">
              입장 QR이 아직 발급되지 않았습니다.
              <br />
              공연 입장 전에 다시 확인해 주세요.
            </p>
          )}
          {state.status === 'error' && <p className="error">{state.message}</p>}
          {state.status === 'ready' && (
            <QRCodeSVG value={state.qr.token} size={220} bgColor="#ffffff" fgColor="#000000" level="M" marginSize={2} />
          )}
        </div>

        <button className="btn btn-primary btn-block" onClick={onClose}>닫기</button>
      </div>
    </div>
  )
}
