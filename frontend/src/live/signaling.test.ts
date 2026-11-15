import { describe, expect, it } from 'vitest'
import { parseServerMessage, serializeClientMessage } from './signaling'

describe('serializeClientMessage', () => {
  it('serializes join messages for both roles', () => {
    expect(JSON.parse(serializeClientMessage({ type: 'join', role: 'broadcaster', token: 'jwt.token.value' }))).toEqual({
      type: 'join',
      role: 'broadcaster',
      token: 'jwt.token.value',
    })
    expect(JSON.parse(serializeClientMessage({ type: 'join', role: 'viewer' }))).toEqual({ type: 'join', role: 'viewer' })
  })

  it('keeps the target and SDP of an offer', () => {
    const text = serializeClientMessage({ type: 'offer', to: 'v1', sdp: { type: 'offer', sdp: 'v=0' } })
    expect(JSON.parse(text)).toEqual({ type: 'offer', to: 'v1', sdp: { type: 'offer', sdp: 'v=0' } })
  })
})

describe('parseServerMessage', () => {
  it('parses joined and viewer events', () => {
    expect(
      parseServerMessage('{"type":"joined","role":"viewer","peerId":"p","viewerCount":2,"broadcasterConnected":false}'),
    ).toEqual({ type: 'joined', role: 'viewer', peerId: 'p', viewerCount: 2, broadcasterConnected: false })
    expect(parseServerMessage('{"type":"viewer-joined","viewerId":"v1","viewerCount":1}')).toEqual({
      type: 'viewer-joined',
      viewerId: 'v1',
      viewerCount: 1,
    })
    expect(parseServerMessage('{"type":"viewer-left","viewerId":"v1","viewerCount":0}')).toEqual({
      type: 'viewer-left',
      viewerId: 'v1',
      viewerCount: 0,
    })
  })

  it('parses relayed SDP and ICE messages with the sender id', () => {
    expect(parseServerMessage('{"type":"offer","from":"broadcaster","sdp":{"type":"offer","sdp":"v=0"}}')).toEqual({
      type: 'offer',
      from: 'broadcaster',
      sdp: { type: 'offer', sdp: 'v=0' },
    })
    expect(parseServerMessage('{"type":"ice-candidate","from":"v1","candidate":{"candidate":"candidate:1"}}')).toEqual({
      type: 'ice-candidate',
      from: 'v1',
      candidate: { candidate: 'candidate:1' },
    })
  })

  it('parses end, error and pong frames', () => {
    expect(parseServerMessage('{"type":"broadcast-ended"}')).toEqual({ type: 'broadcast-ended' })
    expect(parseServerMessage('{"type":"error","code":"FORBIDDEN","message":"no"}')).toEqual({
      type: 'error',
      code: 'FORBIDDEN',
      message: 'no',
    })
    expect(parseServerMessage('{"type":"pong"}')).toEqual({ type: 'pong' })
  })

  it('rejects malformed, incomplete and unknown frames', () => {
    expect(parseServerMessage('not json')).toBeNull()
    expect(parseServerMessage('42')).toBeNull()
    expect(parseServerMessage('null')).toBeNull()
    expect(parseServerMessage('{"type":"mystery"}')).toBeNull()
    expect(parseServerMessage('{"type":"offer","from":"broadcaster"}')).toBeNull() // no sdp
    expect(parseServerMessage('{"type":"viewer-joined","viewerCount":1}')).toBeNull() // no viewerId
    expect(parseServerMessage('{"type":"joined","role":"admin","peerId":"p","viewerCount":0}')).toBeNull()
  })
})
