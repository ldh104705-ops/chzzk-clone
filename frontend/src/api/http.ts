import { tokenStore, type TokenStore } from '../auth/tokenStore'
import { buildUrl } from './url'

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function parseError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json()
    if (body && typeof body.message === 'string') return new ApiError(res.status, body.message)
  } catch {
    // fall through to the generic message
  }
  return new ApiError(res.status, `요청에 실패했습니다 (HTTP ${res.status})`)
}

export interface HttpDeps {
  fetchFn?: typeof fetch
  tokens?: TokenStore
}

/** The Authorization header for a token, or an empty object when logged out. */
export function authHeaders(token: string | null): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/**
 * fetch() against the API: adds `Authorization: Bearer <token>` when logged in, and when the server answers 401
 * to a request that carried a token, that token is expired/invalid/logged out, so it is cleared (the auth context
 * notices and shows the logged-out state). A 401 without a token (e.g. wrong password) changes nothing.
 */
export async function apiFetch(path: string, init: RequestInit = {}, deps: HttpDeps = {}): Promise<Response> {
  const tokens = deps.tokens ?? tokenStore
  const fetchFn = deps.fetchFn ?? fetch
  const token = tokens.get()
  const headers = new Headers(init.headers)
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', authHeaders(token).Authorization)
  }
  const res = await fetchFn(buildUrl(path), { ...init, headers })
  if (res.status === 401 && token && tokens.get() === token) {
    tokens.clear()
  }
  return res
}

/** apiFetch + throws ApiError for non-2xx responses. */
export async function apiRequest(path: string, init: RequestInit = {}, deps: HttpDeps = {}): Promise<Response> {
  const res = await apiFetch(path, init, deps)
  if (!res.ok) throw await parseError(res)
  return res
}

export function jsonBody(body: unknown): RequestInit {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}
