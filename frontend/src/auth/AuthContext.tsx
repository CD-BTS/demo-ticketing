import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { registerUser } from '../api/bts'

const USER_KEY = 'fds.userId'

interface AuthValue {
  userId: string | null
  login: (userId: string) => Promise<void>
  logout: () => void
}

const Ctx = createContext<AuthValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<string | null>(() => {
    try {
      return localStorage.getItem(USER_KEY)
    } catch {
      return null
    }
  })

  // 사용자 ID 기반 식별: BTS에 등록(POST /users) 후 세션에 저장
  const login = useCallback(async (id: string) => {
    await registerUser(id)
    try {
      localStorage.setItem(USER_KEY, id)
    } catch {
      /* ignore */
    }
    setUserId(id)
  }, [])

  const logout = useCallback(() => {
    try {
      localStorage.removeItem(USER_KEY)
    } catch {
      /* ignore */
    }
    setUserId(null)
  }, [])

  const value = useMemo(() => ({ userId, login, logout }), [userId, login, logout])
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAuth must be used inside AuthProvider')
  return v
}
