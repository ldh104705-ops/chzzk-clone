import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import {
  createComment,
  deleteComment,
  listComments,
  MAX_COMMENT_LENGTH,
  updateComment,
  type Comment,
} from '../api/comments'
import { useAuth } from '../auth/AuthContext'
import { formatDateTime } from '../lib/format'

const PAGE_SIZE = 30
/** Live broadcasts refresh comments this often. Polling is enough here: no extra server connection to keep alive. */
const LIVE_POLL_MS = 4000

interface Props {
  broadcastId: number
  /** Poll for new comments while watching a live broadcast. */
  live: boolean
}

/** Comment list (public) + write form (login) + edit/delete on the user's own comments. React escapes all text. */
export default function CommentSection({ broadcastId, live }: Props) {
  const { user } = useAuth()
  const location = useLocation()
  const [items, setItems] = useState<Comment[]>([])
  const [total, setTotal] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [nextPage, setNextPage] = useState(1)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [draft, setDraft] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editDraft, setEditDraft] = useState('')
  const loadedExtra = useRef(0) // how many comments beyond the first page are shown

  /** Loads the newest page. Older pages already loaded with "더 보기" are kept below it. */
  const refresh = useCallback(async () => {
    try {
      const page = await listComments(broadcastId, 0, PAGE_SIZE)
      setItems((old) => {
        if (page.items.length === 0) return []
        const fresh = new Set(page.items.map((c) => c.id))
        const oldestShown = page.items.at(-1)?.id // ids grow with time: keep only older, already-loaded comments below the new page
        const older = old.filter((c) => !fresh.has(c.id) && (oldestShown === undefined || c.id < oldestShown))
        return [...page.items, ...older]
      })
      setTotal(page.totalElements)
      if (loadedExtra.current === 0) setHasNext(page.hasNext)
      setLoadError(null)
      setLoaded(true)
    } catch (e) {
      setLoadError((e as Error).message)
    }
  }, [broadcastId])

  useEffect(() => {
    setItems([])
    setNextPage(1)
    loadedExtra.current = 0
    setLoaded(false)
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (!live) return
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, LIVE_POLL_MS)
    return () => clearInterval(timer)
  }, [live, refresh])

  async function loadMore() {
    try {
      const page = await listComments(broadcastId, nextPage, PAGE_SIZE)
      setItems((old) => [...old, ...page.items.filter((c) => !old.some((o) => o.id === c.id))])
      setHasNext(page.hasNext)
      setNextPage(nextPage + 1)
      loadedExtra.current += page.items.length
    } catch (e) {
      setActionError((e as Error).message)
    }
  }

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setActionError(null)
    try {
      await action()
    } catch (e) {
      setActionError((e as Error).message) // e.g. 403 "댓글 작성자만 할 수 있습니다." or 401
    } finally {
      setBusy(false)
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const content = draft.trim()
    if (content === '') return setActionError('댓글 내용을 입력해 주세요.')
    if ([...content].length > MAX_COMMENT_LENGTH) return setActionError(`댓글은 ${MAX_COMMENT_LENGTH}자 이하여야 합니다.`)
    void run(async () => {
      await createComment(broadcastId, content)
      setDraft('')
      await refresh()
    })
  }

  function saveEdit(id: number) {
    const content = editDraft.trim()
    if (content === '') return setActionError('댓글 내용을 입력해 주세요.')
    if ([...content].length > MAX_COMMENT_LENGTH) return setActionError(`댓글은 ${MAX_COMMENT_LENGTH}자 이하여야 합니다.`)
    void run(async () => {
      await updateComment(id, content)
      setEditingId(null)
      await refresh()
    })
  }

  function remove(id: number) {
    if (!window.confirm('이 댓글을 삭제할까요?')) return
    void run(async () => {
      await deleteComment(id)
      setItems((old) => old.filter((c) => c.id !== id))
      setTotal((t) => Math.max(0, t - 1))
    })
  }

  return (
    <section className="comments" aria-label="댓글">
      <h2 className="section-title">댓글 ({total})</h2>
      {live && <p className="state">라이브 방송 중에는 {LIVE_POLL_MS / 1000}초마다 새 댓글을 불러옵니다.</p>}

      {user ? (
        <form onSubmit={onSubmit} className="comment-form">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={MAX_COMMENT_LENGTH}
            rows={2}
            placeholder={`${user.nickname}(으)로 댓글 쓰기`}
          />
          <button type="submit" className="button primary" disabled={busy}>
            등록
          </button>
        </form>
      ) : (
        <p className="notice info">
          댓글을 쓰려면 <Link to="/login" state={{ from: location.pathname }}>로그인</Link>해 주세요. (읽기는 로그인 없이 가능합니다.)
        </p>
      )}
      {actionError && <p className="field-error">{actionError}</p>}
      {loadError && <p className="field-error">댓글을 불러오지 못했습니다: {loadError}</p>}

      {loaded && items.length === 0 && <p className="state">아직 댓글이 없습니다.</p>}
      <ul className="comment-list">
        {items.map((c) => {
          const mine = user !== null && user.id === c.authorId
          return (
            <li key={c.id} className="comment">
              <div className="comment-head">
                <strong>{c.authorNickname}</strong>
                <time dateTime={c.createdAt}>{formatDateTime(c.createdAt)}</time>
                {c.updatedAt !== c.createdAt && <span className="muted">(수정됨)</span>}
              </div>
              {editingId === c.id ? (
                <div className="comment-form">
                  <textarea value={editDraft} onChange={(e) => setEditDraft(e.target.value)} maxLength={MAX_COMMENT_LENGTH} rows={2} />
                  <button type="button" className="button primary" disabled={busy} onClick={() => saveEdit(c.id)}>
                    저장
                  </button>
                  <button type="button" className="button" onClick={() => setEditingId(null)}>
                    취소
                  </button>
                </div>
              ) : (
                <p className="comment-body">{c.content}</p>
              )}
              {mine && editingId !== c.id && (
                <div className="comment-actions">
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => {
                      setEditingId(c.id)
                      setEditDraft(c.content)
                    }}
                  >
                    수정
                  </button>
                  <button type="button" className="link-button" onClick={() => remove(c.id)} disabled={busy}>
                    삭제
                  </button>
                </div>
              )}
            </li>
          )
        })}
      </ul>
      {hasNext && (
        <button type="button" className="button" onClick={loadMore}>
          댓글 더 보기
        </button>
      )}
    </section>
  )
}
