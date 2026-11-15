import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { deleteBroadcast, getBroadcast, resolvePlaybackUrl, updateBroadcastTitle, type Broadcast } from '../api/broadcasts'
import { useAuth } from '../auth/AuthContext'
import CommentSection from '../components/CommentSection'
import DonationPanel from '../components/DonationPanel'
import LiveViewer from '../components/LiveViewer'
import StatusBadge from '../components/StatusBadge'
import TypeBadge from '../components/TypeBadge'
import { formatBytes, formatDateTime } from '../lib/format'

export default function BroadcastDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { user } = useAuth()
  const [broadcast, setBroadcast] = useState<Broadcast | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [titleDraft, setTitleDraft] = useState('')
  const [manageError, setManageError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!id) return
    setBroadcast(null)
    setError(null)
    getBroadcast(id)
      .then(setBroadcast)
      .catch((e: Error) => setError(e.message))
  }, [id])

  // The viewer learns over the signaling socket that the broadcast ended: flip the badge without a reload.
  const markEnded = useCallback(
    () => setBroadcast((b) => (b && b.status !== 'ENDED' ? { ...b, status: 'ENDED', endedAt: new Date().toISOString() } : b)),
    [],
  )

  if (error) return <p className="state error">{error}</p>
  if (!broadcast) return <p className="state">불러오는 중...</p>

  const isLive = broadcast.type === 'LIVE'
  // Buttons are only a convenience: the server answers 403 to anyone but the owner.
  const isOwner = user !== null && broadcast.ownerId === user.id

  async function saveTitle(e: FormEvent) {
    e.preventDefault()
    if (!broadcast) return
    const title = titleDraft.trim()
    if (title === '') return setManageError('제목을 입력해 주세요.')
    if (title.length > 200) return setManageError('제목은 200자 이하여야 합니다.')
    setBusy(true)
    setManageError(null)
    try {
      setBroadcast(await updateBroadcastTitle(broadcast.id, title))
      setEditing(false)
    } catch (err) {
      setManageError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!broadcast) return
    if (!window.confirm('이 방송을 삭제할까요? 영상 파일과 댓글도 함께 삭제되며 되돌릴 수 없습니다.')) return
    setBusy(true)
    setManageError(null)
    try {
      await deleteBroadcast(broadcast.id)
      navigate('/')
    } catch (err) {
      setManageError((err as Error).message)
      setBusy(false)
    }
  }

  return (
    <article>
      <p>
        <Link to="/">&larr; 목록으로</Link>
      </p>
      {isLive ? (
        <LiveViewer broadcast={broadcast} onEnded={markEnded} />
      ) : (
        <video className="player" controls preload="metadata" src={resolvePlaybackUrl(broadcast)} />
      )}
      {editing ? (
        <form onSubmit={saveTitle} className="comment-form">
          <input value={titleDraft} onChange={(e) => setTitleDraft(e.target.value)} maxLength={200} aria-label="방송 제목" />
          <button type="submit" className="button primary" disabled={busy}>
            저장
          </button>
          <button type="button" className="button" onClick={() => setEditing(false)}>
            취소
          </button>
        </form>
      ) : (
        <h1>{broadcast.title}</h1>
      )}
      <div className="card-meta">
        <TypeBadge type={broadcast.type} />
        <StatusBadge status={broadcast.status} />
        {broadcast.ownerNickname && <span>{broadcast.ownerNickname}</span>}
        <time dateTime={broadcast.createdAt}>{formatDateTime(broadcast.createdAt)}</time>
      </div>
      {isOwner && !editing && (
        <div className="controls">
          <button
            type="button"
            className="button"
            onClick={() => {
              setTitleDraft(broadcast.title)
              setEditing(true)
            }}
          >
            제목 수정
          </button>
          {isLive && broadcast.status !== 'ENDED' && (
            <Link to={`/live/${broadcast.id}/studio`} className="button">
              스튜디오 열기
            </Link>
          )}
          <button type="button" className="button danger" onClick={remove} disabled={busy}>
            방송 삭제
          </button>
        </div>
      )}
      {manageError && <p className="field-error">{manageError}</p>}
      {isLive ? (
        <dl className="details">
          <dt>방송 시작</dt>
          <dd>{broadcast.startedAt ? formatDateTime(broadcast.startedAt) : '아직 시작 전'}</dd>
          {broadcast.endedAt && (
            <>
              <dt>방송 종료</dt>
              <dd>{formatDateTime(broadcast.endedAt)}</dd>
            </>
          )}
        </dl>
      ) : (
        <dl className="details">
          <dt>영상 위치</dt>
          <dd>{broadcast.sourceType === 'FILE' ? '업로드한 파일' : '외부 URL'}</dd>
          {broadcast.sourceType === 'FILE' && (
            <>
              <dt>파일</dt>
              <dd>
                {broadcast.originalFileName} ({formatBytes(broadcast.sizeBytes)})
              </dd>
            </>
          )}
          {broadcast.sourceType === 'URL' && (
            <>
              <dt>URL</dt>
              <dd className="break">{broadcast.playbackUrl}</dd>
            </>
          )}
        </dl>
      )}
      <DonationPanel
        broadcastId={broadcast.id}
        ownerId={broadcast.ownerId}
        ownerNickname={broadcast.ownerNickname}
        live={isLive && broadcast.status !== 'ENDED'}
      />
      <CommentSection broadcastId={broadcast.id} live={isLive && broadcast.status !== 'ENDED'} />
    </article>
  )
}
