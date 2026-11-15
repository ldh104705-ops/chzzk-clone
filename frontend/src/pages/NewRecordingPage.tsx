import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createRecordedBroadcast } from '../api/broadcasts'
import { validateRecordedForm, type RecordedFormErrors, type SourceMode } from '../lib/validation'

export default function NewRecordingPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [mode, setMode] = useState<SourceMode>('file')
  const [file, setFile] = useState<File | null>(null)
  const [videoUrl, setVideoUrl] = useState('')
  const [errors, setErrors] = useState<RecordedFormErrors>({})
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setSubmitError(null)
    const found = validateRecordedForm({ title, mode, file, videoUrl })
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setSubmitting(true)
    try {
      const created = await createRecordedBroadcast({
        title,
        file: mode === 'file' ? file : null,
        videoUrl: mode === 'url' ? videoUrl : undefined,
      })
      navigate(`/broadcasts/${created.id}`)
    } catch (err) {
      setSubmitError((err as Error).message)
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={onSubmit} className="form" noValidate>
      <h1>녹화방송 등록</h1>

      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} />
        {errors.title && <span className="field-error">{errors.title}</span>}
      </label>

      <fieldset>
        <legend>영상 등록 방식</legend>
        <label className="inline">
          <input type="radio" checked={mode === 'file'} onChange={() => setMode('file')} /> 파일 업로드
        </label>
        <label className="inline">
          <input type="radio" checked={mode === 'url'} onChange={() => setMode('url')} /> 영상 URL 연결
        </label>
      </fieldset>

      {mode === 'file' ? (
        <label>
          영상 파일
          <input type="file" accept="video/*" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          {errors.file && <span className="field-error">{errors.file}</span>}
        </label>
      ) : (
        <label>
          영상 URL
          <input
            type="url"
            placeholder="https://example.com/video.mp4"
            value={videoUrl}
            onChange={(e) => setVideoUrl(e.target.value)}
          />
          {errors.videoUrl && <span className="field-error">{errors.videoUrl}</span>}
        </label>
      )}

      {submitError && <p className="state error">{submitError}</p>}
      <button type="submit" className="button primary" disabled={submitting}>
        {submitting ? '등록 중...' : '등록'}
      </button>
    </form>
  )
}
