import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { CARD_DIGITS, MAX_CARDS, cardsApi, type PaymentCard } from '../api/cards'
import CardNumberField, { CardHelp } from './CardNumberField'

/** 마이페이지: 계정에 결제 카드를 미리 등록·삭제한다 (등록한 카드는 결제창에서 바로 선택 가능) */
export default function CardManager({ userId }: { userId: string }) {
  const [cards, setCards] = useState<PaymentCard[] | null>(null)
  const [open, setOpen] = useState(false)
  const [digits, setDigits] = useState('')
  const [nickname, setNickname] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setCards(await cardsApi.list(userId))
    } catch (e) {
      setError((e as Error).message)
      setCards([])
    }
  }, [userId])

  useEffect(() => {
    load()
  }, [load])

  const add = async (e: FormEvent) => {
    e.preventDefault()
    if (digits.length !== CARD_DIGITS) return
    setBusy(true)
    setError('')
    try {
      await cardsApi.add(userId, digits, nickname)
      setDigits('') // 입력한 번호는 즉시 비운다 (끝 4자리만 등록됨)
      setNickname('')
      setOpen(false)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '카드 등록에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (c: PaymentCard) => {
    if (!window.confirm(`'${c.nickname}' (···· ${c.last4}) 카드를 삭제할까요?`)) return
    setError('')
    try {
      await cardsApi.remove(userId, c.id)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '카드 삭제에 실패했습니다.')
    }
  }

  const full = (cards?.length ?? 0) >= MAX_CARDS

  return (
    <section className="cards-section" aria-label="결제 카드">
      <div className="cards-head">
        <h2 className="section-title">결제 카드</h2>
        <button className="btn btn-ghost btn-sm" disabled={full} onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? '닫기' : full ? `최대 ${MAX_CARDS}개` : '+ 카드 등록'}
        </button>
      </div>

      {!cards ? (
        <p className="muted">불러오는 중…</p>
      ) : cards.length === 0 ? (
        <p className="muted">등록된 카드가 없습니다. 카드를 등록해 두면 결제할 때 바로 선택할 수 있어요.</p>
      ) : (
        <ul className="saved-cards">
          {cards.map((c) => (
            <li key={c.id}>
              <span>
                <b>{c.nickname}</b> <span className="masked">···· {c.last4}</span>
              </span>
              <button className="btn btn-ghost btn-sm" onClick={() => remove(c)}>삭제</button>
            </li>
          ))}
        </ul>
      )}

      {open && (
        <form className="card-form" onSubmit={add}>
          <label className="field">
            <span>카드 별칭 (선택)</span>
            <input value={nickname} onChange={(e) => setNickname(e.target.value)} maxLength={12} placeholder="예: 내 카드" autoComplete="off" />
          </label>
          <CardNumberField value={digits} onChange={setDigits} disabled={busy} autoFocus />
          <CardHelp count={digits.length} />
          <p className="small muted">보안을 위해 화면과 저장소에는 끝 4자리만 남기고 전체 번호는 보관하지 않습니다.</p>
          <button className="btn btn-primary" disabled={digits.length !== CARD_DIGITS || busy}>
            {busy ? '등록 중…' : '카드 등록'}
          </button>
        </form>
      )}

      {error && <p className="error" role="alert">{error}</p>}
    </section>
  )
}
