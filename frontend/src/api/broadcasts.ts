import { buildUrl } from './url'

export type BroadcastType = 'RECORDED' | 'LIVE'
export type BroadcastStatus = 'READY' | 'LIVE' | 'ENDED'

export interface Broadcast {
  id: number
  title: string
  type: BroadcastType
  status: BroadcastStatus
  sourceType: 'FILE' | 'URL' | 'LIVE'
  /** Absolute URL for external videos, API-relative path for uploaded files, null for live broadcasts. */
  playbackUrl: string | null
  originalFileName: string | null
  contentType: string | null
  sizeBytes: number | null
  createdAt: string
  startedAt: string | null
  endedAt: string | null
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function parseError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json()
    if (body && typeof body.message === 'string') return new ApiError(res.status, body.message)
  } catch {
    // fall through to the generic message
  }
  return new ApiError(res.status, `요청에 실패했습니다 (HTTP ${res.status})`)
}

export interface ListFilter {
  type?: BroadcastType
  status?: BroadcastStatus
}

/** Lists broadcasts (the server puts LIVE ones first). Optional filters mirror ?type=&status=. */
export async function listBroadcasts(filter: ListFilter = {}): Promise<Broadcast[]> {
  const query = new URLSearchParams()
  if (filter.type) query.set('type', filter.type)
  if (filter.status) query.set('status', filter.status)
  const qs = query.toString()
  const res = await fetch(buildUrl('/api/broadcasts' + (qs ? '?' + qs : '')))
  if (!res.ok) throw await parseError(res)
  return res.json()
}

export async function getBroadcast(id: string): Promise<Broadcast> {
  const res = await fetch(buildUrl(`/api/broadcasts/${encodeURIComponent(id)}`))
  if (!res.ok) throw await parseError(res)
  return res.json()
}

export interface RecordedInput {
  title: string
  file?: File | null
  videoUrl?: string
}

export async function createRecordedBroadcast(input: RecordedInput): Promise<Broadcast> {
  const form = new FormData()
  form.append('title', input.title.trim())
  if (input.file) form.append('file', input.file)
  if (input.videoUrl?.trim()) form.append('videoUrl', input.videoUrl.trim())
  const res = await fetch(buildUrl('/api/broadcasts/recorded'), { method: 'POST', body: form })
  if (!res.ok) throw await parseError(res)
  return res.json()
}

/** Full URL the <video> element should load (external URL as-is, uploaded file via the API base). */
export function resolvePlaybackUrl(b: Pick<Broadcast, 'playbackUrl'>): string {
  return b.playbackUrl ? buildUrl(b.playbackUrl) : ''
}

export interface CreatedLiveBroadcast {
  broadcast: Broadcast
  /** Secret shown only once; required to broadcast and to end the broadcast. */
  broadcasterKey: string
}

export const BROADCASTER_KEY_HEADER = 'X-Broadcaster-Key'

export async function createLiveBroadcast(title: string): Promise<CreatedLiveBroadcast> {
  const res = await fetch(buildUrl('/api/broadcasts/live'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: title.trim() }),
  })
  if (!res.ok) throw await parseError(res)
  return res.json()
}

export async function endLiveBroadcast(id: number, broadcasterKey: string): Promise<Broadcast> {
  const res = await fetch(buildUrl('/api/broadcasts/' + id + '/end'), {
    method: 'POST',
    headers: { [BROADCASTER_KEY_HEADER]: broadcasterKey },
  })
  if (!res.ok) throw await parseError(res)
  return res.json()
}

/** STUN/TURN servers configured on the backend (APP_WEBRTC_*). Falls back to a public STUN server. */
export async function getIceServers(): Promise<RTCIceServer[]> {
  try {
    const res = await fetch(buildUrl('/api/live/ice-servers'))
    if (res.ok) {
      const body = (await res.json()) as { iceServers?: RTCIceServer[] }
      if (Array.isArray(body.iceServers)) return body.iceServers
    }
  } catch {
    // use the fallback below
  }
  return [{ urls: 'stun:stun.l.google.com:19302' }]
}
