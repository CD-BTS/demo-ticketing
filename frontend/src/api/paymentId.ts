/**
 * 데모 백엔드가 허용하는 테스트 결제 식별자 (backend: demo.payment-identifiers).
 * 실제 카드번호나 그 해시를 보내지 않는다. 카드 끝 4자리로 3개 중 하나를 고정 선택하므로
 * 같은 카드를 쓰면 계정이 달라도 같은 식별자가 나가 card_reuse_count 실험이 가능하다.
 */
export const PAYMENT_IDENTIFIERS = ['test-pay-shared-01', 'test-pay-shared-02', 'test-pay-shared-03'] as const

export function paymentIdentifierFor(last4: string): string {
  const n = parseInt(last4, 10)
  return PAYMENT_IDENTIFIERS[(Number.isNaN(n) ? 0 : n) % PAYMENT_IDENTIFIERS.length]
}
