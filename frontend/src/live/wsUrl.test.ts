import { describe, expect, it } from 'vitest'
import { buildWsUrl, signalingUrl } from './wsUrl'

const http = { protocol: 'http:', host: 'localhost:5173' }
const https = { protocol: 'https:', host: '192.168.0.5:5173' }

describe('buildWsUrl', () => {
  it('uses ws on an http page and wss on an https page when there is no base URL', () => {
    expect(buildWsUrl('/ws/live/1', '', http)).toBe('ws://localhost:5173/ws/live/1')
    expect(buildWsUrl('/ws/live/1', '', https)).toBe('wss://192.168.0.5:5173/ws/live/1')
  })

  it('maps the base URL scheme: http -> ws, https -> wss', () => {
    expect(buildWsUrl('/ws/live/1', 'http://localhost:8080', https)).toBe('ws://localhost:8080/ws/live/1')
    expect(buildWsUrl('/ws/live/1', 'https://api.example.com', http)).toBe('wss://api.example.com/ws/live/1')
  })

  it('tolerates trailing slashes and a missing leading slash', () => {
    expect(buildWsUrl('ws/live/1', 'https://api.example.com/', http)).toBe('wss://api.example.com/ws/live/1')
  })
})

describe('signalingUrl', () => {
  it('points at the broadcast-specific endpoint', () => {
    expect(signalingUrl(42, '', https)).toBe('wss://192.168.0.5:5173/ws/live/42')
    expect(signalingUrl('7', 'https://api.example.com', http)).toBe('wss://api.example.com/ws/live/7')
  })
})
