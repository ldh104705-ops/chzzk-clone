import { describe, expect, it } from 'vitest'
import { PeerBook } from './peerBook'

class FakePeer {
  closed = false
  close() {
    this.closed = true
  }
}

describe('PeerBook', () => {
  it('tracks one peer per viewer id', () => {
    const book = new PeerBook<FakePeer>()
    book.set('a', new FakePeer())
    book.set('b', new FakePeer())
    expect(book.size).toBe(2)
    expect(book.ids()).toEqual(['a', 'b'])
    expect(book.has('a')).toBe(true)
  })

  it('closes the previous peer when an id is replaced', () => {
    const book = new PeerBook<FakePeer>()
    const first = new FakePeer()
    const second = new FakePeer()
    book.set('a', first)
    book.set('a', second)
    expect(first.closed).toBe(true)
    expect(second.closed).toBe(false)
    expect(book.get('a')).toBe(second)
    expect(book.size).toBe(1)
  })

  it('closes and forgets a removed peer; unknown ids are a no-op', () => {
    const book = new PeerBook<FakePeer>()
    const peer = new FakePeer()
    book.set('a', peer)
    expect(book.remove('a')).toBe(true)
    expect(peer.closed).toBe(true)
    expect(book.remove('a')).toBe(false)
    expect(book.size).toBe(0)
  })

  it('removeIfCurrent ignores events from a connection that was already replaced', () => {
    const book = new PeerBook<FakePeer>()
    const old = new FakePeer()
    const current = new FakePeer()
    book.set('a', old)
    book.set('a', current)
    expect(book.removeIfCurrent('a', old)).toBe(false)
    expect(current.closed).toBe(false)
    expect(book.removeIfCurrent('a', current)).toBe(true)
  })

  it('closeAll closes everything', () => {
    const book = new PeerBook<FakePeer>()
    const peers = [new FakePeer(), new FakePeer()]
    book.set('a', peers[0])
    book.set('b', peers[1])
    book.closeAll()
    expect(peers.every((p) => p.closed)).toBe(true)
    expect(book.size).toBe(0)
  })
})
