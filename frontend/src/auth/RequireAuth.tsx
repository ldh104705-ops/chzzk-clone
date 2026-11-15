import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

/**
 * Sends logged-out visitors to /login (and back here afterwards). This is only a convenience: the server
 * rejects the protected API calls with 401/403 regardless of what the UI shows.
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) return <p className="state">로그인 상태를 확인하는 중...</p>
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  return <>{children}</>
}
