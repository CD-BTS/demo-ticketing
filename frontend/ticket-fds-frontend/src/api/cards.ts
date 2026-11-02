/**
 * 계정에 등록하는 결제 카드 API.
 *
 * 카드번호 전체는 어디에도 저장하지 않는다. 등록 시에는 끝 4자리와 별칭만 남기고(mock),
 * 실제 서비스에서는 PG사 토큰화로 대체해야 한다 (카드번호가 우리 서버·프론트 저장소에 남으면 안 됨).
 * 실제 API가 확정되면 `real` 의 경로·응답만 맞추고 VITE_USE_MOCK_CARDS=false 로 바꾸면 된다.
 */
import { ApiError, request, sleep } from './client'

export interface PaymentCard {
  id: string
  nickname: string
  last4: string // 화면에는 "···· 0345" 로만 보여 준다
  createdAt: string
}

export const MAX_CARDS = 5
export const CARD_DIGITS = 15 // 실제 카드번호 입력을 막기 위한 가상 번호 자리수

export interface CardApi {
  list(userId: string): Promise<PaymentCard[]>
  /** cardNumber 는 숫자 15자리. 끝 4자리와 별칭만 보관된다. */
  add(userId: string, cardNumber: string, nickname?: string): Promise<PaymentCard>
  remove(userId: string, cardId: string): Promise<void>
}

const USE_MOCK_CARDS = import.meta.env.VITE_USE_MOCK_CARDS !== 'false'

// ---- mock (localStorage, 끝 4자리만 저장) ----
const key = (userId: string) => `fds.mock.cards.${userId}`

function load(userId: string): PaymentCard[] {
  try {
    const raw = localStorage.getItem(key(userId))
    if (raw) return JSON.parse(raw) as PaymentCard[]
  } catch {
    /* ignore */
  }
  return []
}
function save(userId: string, cards: PaymentCard[]) {
  try {
    localStorage.setItem(key(userId), JSON.stringify(cards))
  } catch {
    /* ignore */
  }
}

const mock: CardApi = {
  async list(userId) {
    await sleep(80)
    return load(userId)
  },
  async add(userId, cardNumber, nickname) {
    await sleep(150)
    if (!new RegExp(`^\\d{${CARD_DIGITS}}$`).test(cardNumber)) {
      throw new ApiError('INVALID_CARD', `카드번호는 숫자 ${CARD_DIGITS}자리로 입력해 주세요.`, 400)
    }
    const cards = load(userId)
    if (cards.length >= MAX_CARDS) {
      throw new ApiError('CARD_LIMIT', `카드는 최대 ${MAX_CARDS}개까지 등록할 수 있습니다.`, 409)
    }
    const card: PaymentCard = {
      id: `card_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`,
      nickname: nickname?.trim() || `카드 ${cards.length + 1}`,
      last4: cardNumber.slice(-4), // ← 전체 번호는 버린다
      createdAt: new Date().toISOString(),
    }
    save(userId, [...cards, card])
    return card
  },
  async remove(userId, cardId) {
    await sleep(80)
    save(userId, load(userId).filter((c) => c.id !== cardId))
  },
}

// ---- real (TODO: 경로·응답은 가정. 실제로는 PG 토큰화 후 토큰만 서버에 등록해야 한다) ----
const real: CardApi = {
  list: (userId) => request('GET', `/users/${encodeURIComponent(userId)}/cards`),
  add: (userId, cardNumber, nickname) => request('POST', `/users/${encodeURIComponent(userId)}/cards`, { cardNumber, nickname }),
  remove: (userId, cardId) => request('DELETE', `/users/${encodeURIComponent(userId)}/cards/${cardId}`),
}

export const cardsApi: CardApi = USE_MOCK_CARDS ? mock : real
