import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (username.trim() === '' || password === '') return setError('아이디와 비밀번호를 입력해 주세요.')
    setError(null)
    setSubmitting(true)
    try {
      await login(username.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      setError((err as Error).message)
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={onSubmit} className="form" noValidate>
      <h1>로그인</h1>
      <label>
        아이디
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
      </label>
      <label>
        비밀번호
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
      </label>
      {error && <p className="field-error">{error}</p>}
      <button type="submit" className="button primary" disabled={submitting}>
        {submitting ? '로그인 중...' : '로그인'}
      </button>
      <p className="state">
        계정이 없나요? <Link to="/signup" state={{ from }}>회원가입</Link>
      </p>
    </form>
  )
}
