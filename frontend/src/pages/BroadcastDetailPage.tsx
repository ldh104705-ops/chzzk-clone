import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getBroadcast, resolvePlaybackUrl, type Broadcast } from '../api/broadcasts'
import TypeBadge from '../components/TypeBadge'
import { formatBytes, formatDateTime } from '../lib/format'

export default function BroadcastDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [broadcast, setBroadcast] = useState<Broadcast | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    setBroadcast(null)
    setError(null)
    getBroadcast(id)
      .then(setBroadcast)
      .catch((e: Error) => setError(e.message))
  }, [id])

  if (error) return <p className="state error">{error}</p>
  if (!broadcast) return <p className="state">불러오는 중...</p>

  return (
    <article>
      <p>
        <Link to="/">&larr; 목록으로</Link>
      </p>
      <video className="player" controls preload="metadata" src={resolvePlaybackUrl(broadcast)} />
      <h1>{broadcast.title}</h1>
      <div className="card-meta">
        <TypeBadge type={broadcast.type} />
        <time dateTime={broadcast.createdAt}>{formatDateTime(broadcast.createdAt)}</time>
      </div>
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
    </article>
  )
}
