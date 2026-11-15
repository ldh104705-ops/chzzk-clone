import { describe, expect, it, vi } from 'vitest'
import { createTokenStore, type TokenStorage } from '../auth/tokenStore'
import { ApiError, apiFetch, apiRequest, authHeaders, jsonBody } from './http'

function newStore(initial?: string) {
  const data = new Map<string, string>()
  const storage: TokenStorage = {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
  const store = createTokenStore(storage)
  if (initial) store.set(initial)
  return store
}

function fakeFetch(status: number, body: unknown = {}) {
  return vi.fn(async (_url: string | URL | Request, _init?: RequestInit) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  ) as unknown as typeof fetch & ReturnType<typeof vi.fn>
}

function sentHeaders(fetchFn: ReturnType<typeof vi.fn>): Headers {
  return new Headers((fetchFn.mock.calls[0][1] as RequestInit).headers)
}

describe('authHeaders', () => {
  it('builds a bearer header only when a token exists', () => {
    expect(authHeaders('abc')).toEqual({ Authorization: 'Bearer abc' })
    expect(authHeaders(null)).toEqual({})
  })
})

describe('apiFetch', () => {
  it('adds the Authorization header when logged in', async () => {
    const fetchFn = fakeFetch(200)
    await apiFetch('/api/auth/me', {}, { fetchFn, tokens: newStore('my.jwt') })
    expect(sentHeaders(fetchFn).get('Authorization')).toBe('Bearer my.jwt')
  })

  it('sends no Authorization header when logged out and keeps the caller headers', async () => {
    const fetchFn = fakeFetch(200)
    await apiFetch('/api/broadcasts', jsonBody({ a: 1 }), { fetchFn, tokens: newStore() })
    const headers = sentHeaders(fetchFn)
    expect(headers.has('Authorization')).toBe(false)
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('does not override an explicit Authorization header', async () => {
    const fetchFn = fakeFetch(200)
    await apiFetch('/x', { headers: { Authorization: 'Bearer explicit' } }, { fetchFn, tokens: newStore('stored') })
    expect(sentHeaders(fetchFn).get('Authorization')).toBe('Bearer explicit')
  })

  it('clears the token when the server answers 401 to a request that carried it', async () => {
    const tokens = newStore('expired.jwt')
    const listener = vi.fn()
    tokens.subscribe(listener)
    const res = await apiFetch('/api/auth/me', {}, { fetchFn: fakeFetch(401), tokens })
    expect(res.status).toBe(401)
    expect(tokens.get()).toBeNull()
    expect(listener).toHaveBeenCalled() // the auth context learns about it through this
  })

  it('keeps the token on other errors, e.g. 403 for a non-owner', async () => {
    const tokens = newStore('valid.jwt')
    await apiFetch('/api/broadcasts/1', { method: 'DELETE' }, { fetchFn: fakeFetch(403), tokens })
    expect(tokens.get()).toBe('valid.jwt')
  })

  it('does not wipe a newer token when an older request comes back 401', async () => {
    const tokens = newStore('old.jwt')
    const slow = vi.fn(async () => {
      tokens.set('new.jwt') // user logged in again while the request was in flight
      return new Response('{}', { status: 401 })
    }) as unknown as typeof fetch
    await apiFetch('/x', {}, { fetchFn: slow, tokens })
    expect(tokens.get()).toBe('new.jwt')
  })
})

describe('apiRequest', () => {
  it('throws an ApiError with the server message and status', async () => {
    const fetchFn = fakeFetch(403, { status: 403, error: 'Forbidden', message: '방송 소유자만 할 수 있습니다.' })
    const error = await apiRequest('/x', {}, { fetchFn, tokens: newStore('t') }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(403)
    expect(error.message).toBe('방송 소유자만 할 수 있습니다.')
  })

  it('returns the response on success', async () => {
    const res = await apiRequest('/x', {}, { fetchFn: fakeFetch(200, { ok: true }), tokens: newStore() })
    expect(await res.json()).toEqual({ ok: true })
  })
})
