import type { Seat } from '../api/types'

interface Props {
  seats: Seat[]
  selected: string[]
  onToggle: (seat: Seat) => void
  disabled?: boolean
}

export default function SeatMap({ seats, selected, onToggle, disabled }: Props) {
  const rows = Array.from(new Set(seats.map((s) => s.row)))
  return (
    <div className="seatmap" role="group" aria-label="좌석 배치도">
      <div className="stage">STAGE</div>
      {rows.map((row) => (
        <div className="seat-row" key={row}>
          <span className="row-label">{row}</span>
          {seats
            .filter((s) => s.row === row)
            .map((s) => {
              const isSel = selected.includes(s.id)
              const state = isSel ? 'selected' : s.heldByMe ? 'mine' : s.status.toLowerCase()
              const clickable = !disabled && (s.status === 'AVAILABLE' || s.heldByMe || isSel)
              return (
                <button
                  key={s.id}
                  type="button"
                  className={`seat seat-${state} grade-${s.grade}`}
                  disabled={!clickable}
                  aria-pressed={isSel}
                  aria-label={`${s.id} ${s.grade}석 ${s.status === 'AVAILABLE' ? '선택 가능' : '선택 불가'}`}
                  title={`${s.id} · ${s.grade} · ${s.price.toLocaleString()}원`}
                  onClick={() => onToggle(s)}
                >
                  {s.number}
                </button>
              )
            })}
        </div>
      ))}
      <ul className="legend">
        <li><i className="seat-sample seat-available" />선택 가능</li>
        <li><i className="seat-sample seat-selected" />내 선택</li>
        <li><i className="seat-sample seat-held" />선점됨</li>
        <li><i className="seat-sample seat-booked" />예매 완료</li>
      </ul>
    </div>
  )
}
