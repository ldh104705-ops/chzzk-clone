import { buildUrl } from './url'

export type BroadcastType = 'RECORDED'
export type BroadcastStatus = 'ENDED'

export interface Broadcast {
  id: number
  title: string
  type: BroadcastType
  status: BroadcastStatus
  sourceType: 'FILE' | 'URL'
  /** Absolute URL for external videos, API-relative path for uploaded files. Use resolvePlaybackUrl(). */
  playbackUrl: string
  originalFileName: string | null
  contentType: string | null
  sizeBytes: number | null
  createdAt: string
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

export async function listBroadcasts(): Promise<Broadcast[]> {
  const res = await fetch(buildUrl('/api/broadcasts'))
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
  return buildUrl(b.playbackUrl)
}
