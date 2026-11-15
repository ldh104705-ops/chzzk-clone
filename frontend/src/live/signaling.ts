/** Wire format of the WebSocket signaling protocol (see LiveSignalingHandler on the backend). */

export type ClientMessage =
  | { type: 'join'; role: 'broadcaster'; token: string }
  | { type: 'join'; role: 'viewer' }
  | { type: 'offer'; to: string; sdp: RTCSessionDescriptionInit }
  | { type: 'answer'; sdp: RTCSessionDescriptionInit }
  | { type: 'ice-candidate'; to?: string; candidate: RTCIceCandidateInit | null }
  | { type: 'ping' }

export type ServerMessage =
  | { type: 'joined'; role: 'broadcaster' | 'viewer'; peerId: string; viewerCount: number; broadcasterConnected?: boolean }
  | { type: 'viewer-joined'; viewerId: string; viewerCount: number }
  | { type: 'viewer-left'; viewerId: string; viewerCount: number }
  | { type: 'offer'; from: string; sdp: RTCSessionDescriptionInit }
  | { type: 'answer'; from: string; sdp: RTCSessionDescriptionInit }
  | { type: 'ice-candidate'; from: string; candidate: RTCIceCandidateInit | null }
  | { type: 'broadcast-ended' }
  | { type: 'error'; code: string; message: string }
  | { type: 'pong' }

export function serializeClientMessage(message: ClientMessage): string {
  return JSON.stringify(message)
}

const isString = (v: unknown): v is string => typeof v === 'string'
const isCount = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const isSdp = (v: unknown): v is RTCSessionDescriptionInit =>
  typeof v === 'object' && v !== null && isString((v as { type?: unknown }).type)

/** Parses a server frame; returns null for anything that is not a well-formed known message. */
export function parseServerMessage(text: string): ServerMessage | null {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof raw !== 'object' || raw === null) return null
  const m = raw as Record<string, unknown>

  switch (m.type) {
    case 'joined':
      if ((m.role === 'broadcaster' || m.role === 'viewer') && isString(m.peerId) && isCount(m.viewerCount)) {
        return {
          type: 'joined',
          role: m.role,
          peerId: m.peerId,
          viewerCount: m.viewerCount,
          broadcasterConnected: typeof m.broadcasterConnected === 'boolean' ? m.broadcasterConnected : undefined,
        }
      }
      return null
    case 'viewer-joined':
    case 'viewer-left':
      return isString(m.viewerId) && isCount(m.viewerCount)
        ? { type: m.type, viewerId: m.viewerId, viewerCount: m.viewerCount }
        : null
    case 'offer':
    case 'answer':
      return isString(m.from) && isSdp(m.sdp) ? { type: m.type, from: m.from, sdp: m.sdp } : null
    case 'ice-candidate':
      if (!isString(m.from)) return null
      return {
        type: 'ice-candidate',
        from: m.from,
        candidate: typeof m.candidate === 'object' ? (m.candidate as RTCIceCandidateInit | null) : null,
      }
    case 'broadcast-ended':
      return { type: 'broadcast-ended' }
    case 'error':
      return { type: 'error', code: isString(m.code) ? m.code : 'UNKNOWN', message: isString(m.message) ? m.message : '' }
    case 'pong':
      return { type: 'pong' }
    default:
      return null
  }
}
