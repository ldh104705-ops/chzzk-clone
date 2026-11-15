import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { validateSignupForm } from '../lib/validation'

export default function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [errors, setErrors] = useState<ReturnType<typeof validateSignupForm>>({})
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitError(null)
    const found = validateSignupForm({ username, password, nickname })
    setErrors(found)
    if (Object.keys(found).length > 0) return
    setSubmitting(true)
    try {
      await signup(username.trim(), password, nickname.trim()) // creates the account, then logs in
      navigate(from, { replace: true })
    } catch (err) {
      setSubmitError((err as Error).message)
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={onSubmit} className="form" noValidate>
      <h1>회원가입</h1>
      <label>
        아이디 (영문 소문자·숫자·밑줄 3~30자)
        <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={30} autoComplete="username" />
        {errors.username && <span className="field-error">{errors.username}</span>}
      </label>
      <label>
        비밀번호 (8자 이상)
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
        {errors.password && <span className="field-error">{errors.password}</span>}
      </label>
      <label>
        닉네임 (20자 이하)
        <input value={nickname} onChange={(e) => setNickname(e.target.value)} maxLength={20} />
        {errors.nickname && <span className="field-error">{errors.nickname}</span>}
      </label>
      {submitError && <p className="field-error">{submitError}</p>}
      <button type="submit" className="button primary" disabled={submitting}>
        {submitting ? '가입 중...' : '가입하고 로그인'}
      </button>
      <p className="state">
        이미 계정이 있나요? <Link to="/login" state={{ from }}>로그인</Link>
      </p>
    </form>
  )
}
