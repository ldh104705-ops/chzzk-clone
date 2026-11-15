import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createLiveBroadcast } from '../api/broadcasts'
import { checkMediaSupport } from '../live/media'

export default function NewLivePage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [titleError, setTitleError] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // Creating the broadcast needs a login (RequireAuth); in addition the camera only works on HTTPS: warn before the studio page.
  const mediaProblem = checkMediaSupport({
    isSecureContext: window.isSecureContext,
    mediaDevices: navigator.mediaDevices,
  })

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitError(null)
    if (title.trim() === '') return setTitleError('제목을 입력해 주세요.')
    if (title.trim().length > 200) return setTitleError('제목은 200자 이하여야 합니다.')
    setTitleError(null)

    setSubmitting(true)
    try {
      const created = await createLiveBroadcast(title)
      navigate(`/live/${created.id}/studio`)
    } catch (err) {
      setSubmitError((err as Error).message)
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={onSubmit} className="form" noValidate>
      <h1>라이브 방송 시작</h1>
      {mediaProblem && <p className="notice">{mediaProblem}</p>}

      <label>
        방송 제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} />
        {titleError && <span className="field-error">{titleError}</span>}
      </label>
      <p className="studio-form-hint">다음 화면에서 카메라·마이크 권한을 허용하고 &quot;방송 시작&quot;을 누르면 송출됩니다.</p>

      {submitError && <p className="field-error">{submitError}</p>}
      <button type="submit" className="button primary" disabled={submitting}>
        {submitting ? '만드는 중...' : '스튜디오로 이동'}
      </button>
    </form>
  )
}
