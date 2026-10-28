import type { ApiEnvelope } from './types'

const BASE = import.meta.env.VITE_API_BASE_URL ?? ''
const TOKEN_KEY = 'fds.token'

export const USE_MOCK_BTS = import.meta.env.VITE_USE_MOCK_BTS !== 'false'
export const USE_MOCK_TICKETING = import.meta.env.VITE_USE_MOCK_TICKETING !== 'false'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

export class ApiError extends Error {
  code: string
  status: number
  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

/** Envelope({success, code, message, data})를 벗겨서 data만 반환. 실패 시 ApiError. */
export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<T> {
  const token = tokenStore.get()
  const res = await fetch(`${BASE}/api/v1${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  let json: ApiEnvelope<T> | null = null
  try {
    json = (await res.json()) as ApiEnvelope<T>
  } catch {
    /* 본문이 JSON이 아닌 경우 */
  }

  if (!res.ok || !json || !json.success) {
    throw new ApiError(
      json?.code ?? `HTTP_${res.status}`,
      json?.message ?? '요청 처리 중 오류가 발생했습니다.',
      res.status,
    )
  }
  return json.data
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
