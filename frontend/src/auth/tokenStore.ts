/**
 * Where the login token (JWT) lives in the browser. localStorage keeps the login across reloads and tabs; the
 * trade-off is that any script on the page could read it (XSS), which is why the app renders user text only
 * through React (escaped) and never as HTML. If storage is blocked (private mode), the token is kept in memory
 * for the current page only.
 */
export type TokenStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export interface TokenStore {
  get(): string | null
  set(token: string): void
  clear(): void
  /** Calls `listener` whenever the token is set or cleared (also by a 401 response). Returns an unsubscribe function. */
  subscribe(listener: () => void): () => void
}

export const TOKEN_KEY = 'chzzk.token'

function defaultStorage(): TokenStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

export function createTokenStore(storage: TokenStorage | null = defaultStorage()): TokenStore {
  let memory: string | null = null
  const listeners = new Set<() => void>()
  const notify = () => listeners.forEach((l) => l())

  return {
    get() {
      try {
        return storage ? storage.getItem(TOKEN_KEY) : memory
      } catch {
        return memory
      }
    },
    set(token) {
      memory = token
      try {
        storage?.setItem(TOKEN_KEY, token)
      } catch {
        // keep the in-memory copy
      }
      notify()
    },
    clear() {
      memory = null
      try {
        storage?.removeItem(TOKEN_KEY)
      } catch {
        // nothing else to do
      }
      notify()
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => void listeners.delete(listener)
    },
  }
}

/** The app-wide token store. */
export const tokenStore: TokenStore = createTokenStore()
