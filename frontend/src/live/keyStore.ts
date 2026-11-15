/**
 * The broadcaster key is kept in sessionStorage (per tab, cleared when the tab closes) so the studio page
 * survives a reload/reconnect. It is only a stream-hijack guard, not a login (that comes in step 3).
 */
export type KeyStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

const prefix = 'chzzk.broadcasterKey.'

function defaultStorage(): KeyStorage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null // storage can be blocked (private mode, disabled cookies)
  }
}

export function saveBroadcasterKey(broadcastId: number | string, key: string, storage: KeyStorage | null = defaultStorage()): void {
  try {
    storage?.setItem(prefix + broadcastId, key)
  } catch {
    // ignore: the user can still broadcast, they just cannot reload the studio page
  }
}

export function loadBroadcasterKey(broadcastId: number | string, storage: KeyStorage | null = defaultStorage()): string | null {
  try {
    return storage?.getItem(prefix + broadcastId) ?? null
  } catch {
    return null
  }
}

export function clearBroadcasterKey(broadcastId: number | string, storage: KeyStorage | null = defaultStorage()): void {
  try {
    storage?.removeItem(prefix + broadcastId)
  } catch {
    // ignore
  }
}
