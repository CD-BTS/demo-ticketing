import type { ChangeEvent } from 'react'
import { CARD_DIGITS } from '../api/cards'

// 15자리를 4-4-4-3 으로 끊어서 보여 준다
export const formatCard = (digits: string) =>
  [digits.slice(0, 4), digits.slice(4, 8), digits.slice(8, 12), digits.slice(12, 15)].filter(Boolean).join(' ')

interface Props {
  value: string // 숫자만
  onChange: (digits: string) => void
  disabled?: boolean
  autoFocus?: boolean
  label?: string
}

/** 가상 카드번호(숫자 15자리) 입력칸. 브라우저 자동완성은 꺼서 실제 카드가 채워지지 않게 한다. */
export default function CardNumberField({ value, onChange, disabled, autoFocus, label = `카드번호 (테스트용 ${CARD_DIGITS}자리)` }: Props) {
  const onInput = (e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value.replace(/\D/g, '').slice(0, CARD_DIGITS))
  return (
    <label className="field">
      <span>{label}</span>
      <input
        name="test-card-number"
        value={formatCard(value)}
        onChange={onInput}
        inputMode="numeric"
        autoComplete="off"
        autoFocus={autoFocus}
        placeholder="0000 0000 0000 000"
        disabled={disabled}
        aria-describedby="card-help"
      />
    </label>
  )
}

export function CardHelp({ count }: { count: number }) {
  return (
    <p id="card-help" className="small muted">
      실제 카드번호는 입력하지 마세요. 임의의 숫자 {CARD_DIGITS}자리면 됩니다. ({count}/{CARD_DIGITS})
    </p>
  )
}
