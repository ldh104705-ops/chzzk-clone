import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listBroadcasts, type Broadcast } from '../api/broadcasts'
import TypeBadge from '../components/TypeBadge'
import { formatDateTime } from '../lib/format'

export default function BroadcastListPage() {
  const [items, setItems] = useState<Broadcast[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listBroadcasts()
      .then(setItems)
      .catch((e: Error) => setError(e.message))
  }, [])

  if (error) return <p className="state error">목록을 불러오지 못했습니다: {error}</p>
  if (!items) return <p className="state">불러오는 중...</p>
  if (items.length === 0) {
    return (
      <p className="state">
        등록된 방송이 없습니다. <Link to="/recordings/new">녹화방송을 등록</Link>해 보세요.
      </p>
    )
  }

  return (
    <>
      <h1>방송 목록</h1>
      <ul className="grid">
        {items.map((b) => (
          <li key={b.id}>
            <Link to={`/broadcasts/${b.id}`} className="card">
              <div className="card-title">{b.title}</div>
              <div className="card-meta">
                <TypeBadge type={b.type} />
                <time dateTime={b.createdAt}>{formatDateTime(b.createdAt)}</time>
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
