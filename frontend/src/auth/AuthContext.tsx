import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as authApi from '../api/auth'
import type { User } from '../api/auth'
import { tokenStore } from './tokenStore'

interface AuthState {
  /** The logged-in user, or null. */
  user: User | null
  /** True while a stored token is being checked against the server (first page load). */
  loading: boolean
  login(username: string, password: string): Promise<void>
  signup(username: string, password: string, nickname: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

/**
 * Keeps the login state. On start, a stored token is verified with GET /api/auth/me; any 401 from any API call
 * clears the token (see apiFetch), which this provider observes and turns into the logged-out state.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.get() !== null)

  useEffect(() => {
    let cancelled = false
    if (tokenStore.get()) {
      authApi
        .fetchMe()
        .then((me) => !cancelled && setUser(me))
        .catch(() => !cancelled && setUser(null)) // a 401 already cleared the token
        .finally(() => !cancelled && setLoading(false))
    }
    const unsubscribe = tokenStore.subscribe(() => {
      if (tokenStore.get() === null) setUser(null)
    })
    return () => {
      cancelled = true
      unsubscribe()
    }
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const result = await authApi.login(username, password)
    tokenStore.set(result.token)
    setUser(result.user)
  }, [])

  const signup = useCallback(
    async (username: string, password: string, nickname: string) => {
      await authApi.signup(username, password, nickname)
      await login(username, password)
    },
    [login],
  )

  const logout = useCallback(async () => {
    try {
      await authApi.logoutOnServer() // invalidates the token on the server
    } catch {
      // offline or already expired: logging out locally is what matters
    }
    tokenStore.clear()
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, loading, login, signup, logout }), [user, loading, login, signup, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
