export const won = (n: number) => `${n.toLocaleString('ko-KR')}원`

export const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })

export const dateShort = (iso: string) =>
  new Date(iso).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' })

export const seatLabel = (ids: string[]) => ids.join(', ')
