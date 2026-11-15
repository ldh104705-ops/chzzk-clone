import { apiRequest, jsonBody } from './http'
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
  /** Owner's user id (null for broadcasts created before login existed). Used only to decide which buttons to show. */
  ownerId: number | null
  ownerNickname: string | null
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
  const res = await apiRequest('/api/broadcasts' + (qs ? '?' + qs : ''))
  return res.json()
}

export async function getBroadcast(id: string): Promise<Broadcast> {
  const res = await apiRequest(`/api/broadcasts/${encodeURIComponent(id)}`)
  return res.json()
}

export interface RecordedInput {
  title: string
  file?: File | null
  videoUrl?: string
}

/** Requires login (the logged-in user becomes the owner). */
export async function createRecordedBroadcast(input: RecordedInput): Promise<Broadcast> {
  const form = new FormData()
  form.append('title', input.title.trim())
  if (input.file) form.append('file', input.file)
  if (input.videoUrl?.trim()) form.append('videoUrl', input.videoUrl.trim())
  const res = await apiRequest('/api/broadcasts/recorded', { method: 'POST', body: form })
  return res.json()
}

/** Full URL the <video> element should load (external URL as-is, uploaded file via the API base). */
export function resolvePlaybackUrl(b: Pick<Broadcast, 'playbackUrl'>): string {
  return b.playbackUrl ? buildUrl(b.playbackUrl) : ''
}

/** Requires login. Only the creator (owner) can stream to the new broadcast. */
export async function createLiveBroadcast(title: string): Promise<Broadcast> {
  const res = await apiRequest('/api/broadcasts/live', { method: 'POST', ...jsonBody({ title: title.trim() }) })
  return res.json()
}

/** Owner only. */
export async function endLiveBroadcast(id: number): Promise<Broadcast> {
  const res = await apiRequest('/api/broadcasts/' + id + '/end', { method: 'POST' })
  return res.json()
}

/** Owner only. */
export async function updateBroadcastTitle(id: number, title: string): Promise<Broadcast> {
  const res = await apiRequest('/api/broadcasts/' + id, { method: 'PATCH', ...jsonBody({ title: title.trim() }) })
  return res.json()
}

/** Owner only. Also removes the stored video file and the comments. */
export async function deleteBroadcast(id: number): Promise<void> {
  await apiRequest('/api/broadcasts/' + id, { method: 'DELETE' })
}

/** STUN/TURN servers configured on the backend (APP_WEBRTC_*). Falls back to a public STUN server. */
export async function getIceServers(): Promise<RTCIceServer[]> {
  try {
    const res = await apiRequest('/api/live/ice-servers')
    const body = (await res.json()) as { iceServers?: RTCIceServer[] }
    if (Array.isArray(body.iceServers)) return body.iceServers
  } catch {
    // use the fallback below
  }
  return [{ urls: 'stun:stun.l.google.com:19302' }]
}
