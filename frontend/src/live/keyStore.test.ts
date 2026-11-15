import { describe, expect, it } from 'vitest'
import { clearBroadcasterKey, loadBroadcasterKey, saveBroadcasterKey, type KeyStorage } from './keyStore'

function memoryStorage(): KeyStorage {
  const data = new Map<string, string>()
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

describe('broadcaster key store', () => {
  it('stores keys per broadcast and can clear them', () => {
    const storage = memoryStorage()
    saveBroadcasterKey(1, 'key-1', storage)
    saveBroadcasterKey(2, 'key-2', storage)
    expect(loadBroadcasterKey(1, storage)).toBe('key-1')
    expect(loadBroadcasterKey(2, storage)).toBe('key-2')
    clearBroadcasterKey(1, storage)
    expect(loadBroadcasterKey(1, storage)).toBeNull()
    expect(loadBroadcasterKey(2, storage)).toBe('key-2')
  })

  it('survives unavailable or throwing storage', () => {
    const fail = () => {
      throw new Error('blocked')
    }
    const broken: KeyStorage = { getItem: fail, setItem: fail, removeItem: fail }
    expect(() => saveBroadcasterKey(1, 'k', broken)).not.toThrow()
    expect(loadBroadcasterKey(1, broken)).toBeNull()
    expect(() => clearBroadcasterKey(1, broken)).not.toThrow()
    expect(loadBroadcasterKey(1, null)).toBeNull()
  })
})
