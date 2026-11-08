import { ApiError, request, USE_MOCK_TICKETING } from './client'

export interface Attempt {
  attemptId: string; concertId: string; stage: string; status: string; sequence: number
  holdId: string | null; bookingId: string | null
}
interface Pending { transitionId: string; stage: string; status: string }
interface LocalAttempt { attemptId: string; clientOccurredAt: string; transitions: Record<string, string>; pending?: Pending[] }
const key = (user: string, concert: string) => `fds.attempt.v1.${user}.${concert}`
export function localAttempt(user: string, concert: string): LocalAttempt | null {
  try { return JSON.parse(sessionStorage.getItem(key(user, concert)) ?? 'null') } catch { return null }
}
export function clearAttempt(user: string, concert: string) { sessionStorage.removeItem(key(user, concert)) }
export function operationTransition(user: string, concert: string, operation: string): string | undefined {
  const local = localAttempt(user, concert)
  if (!local) return undefined
  local.transitions[operation] ??= crypto.randomUUID()
  sessionStorage.setItem(key(user, concert), JSON.stringify(local))
  return local.transitions[operation]
}
export async function startAttempt(user: string, concert: string): Promise<Attempt> {
  let local = localAttempt(user, concert)
  if (!local) {
    local = { attemptId: crypto.randomUUID(), clientOccurredAt: new Date().toISOString(), transitions: {} }
    sessionStorage.setItem(key(user, concert), JSON.stringify(local))
  }
  if (USE_MOCK_TICKETING) return { attemptId: local.attemptId, concertId: concert, stage: 'STARTED', status: 'ENTERED', sequence: 1, holdId: null, bookingId: null }
  return request('PUT', `/concerts/${concert}/booking-attempts/${local.attemptId}`, { clientOccurredAt: local.clientOccurredAt })
}
export async function getAttempt(user: string, concert: string): Promise<Attempt | null> {
  const local = localAttempt(user, concert)
  if (!local) return null
  if (USE_MOCK_TICKETING) return startAttempt(user, concert)
  await flushStages(user, concert)
  return request('GET', `/booking-attempts/${local.attemptId}`)
}
export async function reportStage(user: string, concert: string, stage: string, status = 'ENTERED'): Promise<void> {
  const local = localAttempt(user, concert)
  if (!local || USE_MOCK_TICKETING) return
  // Persist before sending; reload and lost-response retries use the same body.
  const identity = `${stage}:${status}`
  local.transitions[identity] ??= crypto.randomUUID()
  const transitionId = local.transitions[identity]
  local.pending ??= []
  if (!local.pending.some(p => p.transitionId === transitionId)) local.pending.push({ transitionId, stage, status })
  sessionStorage.setItem(key(user, concert), JSON.stringify(local))
  await flushStages(user, concert)
}

// A failed capability lookup is an error, never permission to bypass recording.
export async function attemptsEnabled(): Promise<boolean> {
  if (USE_MOCK_TICKETING) return false
  return (await request<{ enabled: boolean }>('GET', '/booking-attempts/capabilities')).enabled
}
export async function retryTransient<T>(work: () => Promise<T>): Promise<T> {
  for (let n = 0; ; n++) {
    try { return await work() } catch (error) {
      if (n >= 2 || (error instanceof ApiError && error.status < 500 && error.status !== 429)) throw error
      await new Promise(resolve => setTimeout(resolve, 100 * 2 ** n))
    }
  }
}
const flushing = new Map<string, Promise<void>>()
export async function flushStages(user: string, concert: string): Promise<void> {
  const storageKey = key(user, concert)
  const running = flushing.get(storageKey)
  if (running) return running
  const work = (async () => {
    for (;;) {
      const local = localAttempt(user, concert)
      const next = local?.pending?.[0]
      if (!local || !next || USE_MOCK_TICKETING) return
      await retryTransient(() => request('POST', `/booking-attempts/${local.attemptId}/transitions`, next))
      const latest = localAttempt(user, concert)
      if (!latest || latest.attemptId !== local.attemptId) return
      latest.pending = latest.pending?.filter(p => p.transitionId !== next.transitionId)
      sessionStorage.setItem(storageKey, JSON.stringify(latest))
    }
  })()
  flushing.set(storageKey, work)
  try { await work } finally { flushing.delete(storageKey) }
}

// Store only a digest of the CAPTCHA input, including when attempts are disabled.
export async function captchaTransition(user: string, concert: string, challenge: string, answer: string): Promise<string> {
  const bytes = new TextEncoder().encode(`${challenge}:${answer}`)
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), b => b.toString(16).padStart(2, '0')).join('')
  const storageKey = `fds.captcha.v1.${user}.${concert}.${digest}`
  let identity = sessionStorage.getItem(storageKey)
  if (!identity) { identity = crypto.randomUUID();sessionStorage.setItem(storageKey, identity) }
  return identity
}
