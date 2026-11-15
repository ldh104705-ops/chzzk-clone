import { describe, expect, it } from 'vitest'
import { resolvePlaybackUrl } from './broadcasts'
import { buildUrl } from './url'

describe('buildUrl', () => {
  it('uses the same origin when base is empty', () => {
    expect(buildUrl('/api/broadcasts', '')).toBe('/api/broadcasts')
  })

  it('joins base and path without double slashes', () => {
    expect(buildUrl('/api/broadcasts', 'https://api.example.com/')).toBe('https://api.example.com/api/broadcasts')
    expect(buildUrl('api/broadcasts', 'https://api.example.com')).toBe('https://api.example.com/api/broadcasts')
  })

  it('passes absolute URLs through unchanged', () => {
    expect(buildUrl('https://cdn.example.com/a.mp4', 'https://api.example.com')).toBe('https://cdn.example.com/a.mp4')
  })
})

describe('resolvePlaybackUrl', () => {
  it('keeps external URLs as-is', () => {
    expect(resolvePlaybackUrl({ playbackUrl: 'https://example.com/v.mp4' })).toBe('https://example.com/v.mp4')
  })
})
