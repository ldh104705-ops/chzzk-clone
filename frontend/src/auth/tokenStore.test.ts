import { describe, expect, it, vi } from 'vitest'
import { createTokenStore, TOKEN_KEY, type TokenStorage } from './tokenStore'

function memoryStorage(): TokenStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

describe('token store', () => {
  it('saves, reads and clears the token in storage', () => {
    const storage = memoryStorage()
    const store = createTokenStore(storage)
    expect(store.get()).toBeNull()
    store.set('abc.def.ghi')
    expect(store.get()).toBe('abc.def.ghi')
    expect(storage.data.get(TOKEN_KEY)).toBe('abc.def.ghi')
    // A second store on the same storage (another tab / after a reload) sees the login.
    expect(createTokenStore(storage).get()).toBe('abc.def.ghi')
    store.clear()
    expect(store.get()).toBeNull()
    expect(storage.data.has(TOKEN_KEY)).toBe(false)
  })

  it('notifies subscribers on set and clear until they unsubscribe', () => {
    const store = createTokenStore(memoryStorage())
    const listener = vi.fn()
    const unsubscribe = store.subscribe(listener)
    store.set('t')
    store.clear()
    expect(listener).toHaveBeenCalledTimes(2)
    unsubscribe()
    store.set('t2')
    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('falls back to memory when storage is missing or throws', () => {
    const fail = () => {
      throw new Error('blocked')
    }
    const broken = createTokenStore({ getItem: fail, setItem: fail, removeItem: fail })
    broken.set('t')
    expect(broken.get()).toBe('t')
    expect(() => broken.clear()).not.toThrow()
    expect(broken.get()).toBeNull()

    const none = createTokenStore(null)
    none.set('mem')
    expect(none.get()).toBe('mem')
    none.clear()
    expect(none.get()).toBeNull()
  })
})
