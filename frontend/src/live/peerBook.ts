export interface Closable {
  close(): void
}

/**
 * Bookkeeping of the broadcaster's connections: one RTCPeerConnection per viewer id.
 * Replacing an id closes the old connection (a viewer that renegotiates must not leak the previous one).
 */
export class PeerBook<T extends Closable> {
  private readonly peers = new Map<string, T>()

  get size(): number {
    return this.peers.size
  }

  ids(): string[] {
    return [...this.peers.keys()]
  }

  get(id: string): T | undefined {
    return this.peers.get(id)
  }

  has(id: string): boolean {
    return this.peers.has(id)
  }

  /** Stores the peer; any previous peer with the same id is closed first. */
  set(id: string, peer: T): void {
    this.remove(id)
    this.peers.set(id, peer)
  }

  /** Closes and forgets one peer. Returns false when the id is unknown. */
  remove(id: string): boolean {
    const peer = this.peers.get(id)
    if (!peer) return false
    this.peers.delete(id)
    peer.close()
    return true
  }

  /** Removes the id only if it still maps to this exact peer (ignores events from a replaced connection). */
  removeIfCurrent(id: string, peer: T): boolean {
    return this.peers.get(id) === peer ? this.remove(id) : false
  }

  forEach(fn: (peer: T, id: string) => void): void {
    this.peers.forEach((peer, id) => fn(peer, id))
  }

  closeAll(): void {
    this.peers.forEach((peer) => peer.close())
    this.peers.clear()
  }
}
