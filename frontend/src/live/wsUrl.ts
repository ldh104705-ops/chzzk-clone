export interface LocationLike {
  protocol: string
  host: string
}

/**
 * Builds the signaling WebSocket URL.
 * - With a base URL (VITE_API_BASE_URL, separately hosted backend): http -> ws, https -> wss on that host.
 * - Without one: same origin as the page (in dev the Vite proxy forwards /ws to the backend), using wss on https pages.
 */
export function buildWsUrl(
  path: string,
  base: string = import.meta.env.VITE_API_BASE_URL ?? '',
  location: LocationLike = globalThis.location,
): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const trimmedBase = base.trim().replace(/\/+$/, '')
  if (trimmedBase === '') {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    return `${scheme}://${location.host}${normalizedPath}`
  }
  const wsBase = trimmedBase.replace(/^http(s?):\/\//i, (_match, secure: string) => `ws${secure}://`)
  return `${wsBase}${normalizedPath}`
}

/** Signaling endpoint of one live broadcast. */
export function signalingUrl(broadcastId: number | string, base?: string, location?: LocationLike): string {
  return buildWsUrl(`/ws/live/${encodeURIComponent(String(broadcastId))}`, base, location)
}
