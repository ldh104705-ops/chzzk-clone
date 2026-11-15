import { describe, expect, it } from 'vitest'
import { isHttpUrl, validateRecordedForm } from './validation'

const video = { type: 'video/mp4' }

describe('isHttpUrl', () => {
  it('accepts http(s) only', () => {
    expect(isHttpUrl('https://example.com/a.mp4')).toBe(true)
    expect(isHttpUrl('http://localhost:9000/a.mp4')).toBe(true)
    expect(isHttpUrl('ftp://example.com/a.mp4')).toBe(false)
    expect(isHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpUrl('not a url')).toBe(false)
  })
})

describe('validateRecordedForm', () => {
  it('requires a title', () => {
    expect(validateRecordedForm({ title: '  ', mode: 'file', file: video, videoUrl: '' }).title).toBeDefined()
  })

  it('accepts a title with a video file', () => {
    expect(validateRecordedForm({ title: '방송', mode: 'file', file: video, videoUrl: '' })).toEqual({})
  })

  it('rejects missing or non-video files in file mode', () => {
    expect(validateRecordedForm({ title: 't', mode: 'file', file: null, videoUrl: '' }).file).toBeDefined()
    expect(validateRecordedForm({ title: 't', mode: 'file', file: { type: 'text/plain' }, videoUrl: '' }).file).toBeDefined()
  })

  it('validates the URL in url mode and ignores the file', () => {
    expect(validateRecordedForm({ title: 't', mode: 'url', file: null, videoUrl: '' }).videoUrl).toBeDefined()
    expect(validateRecordedForm({ title: 't', mode: 'url', file: null, videoUrl: 'ftp://x/y' }).videoUrl).toBeDefined()
    expect(validateRecordedForm({ title: 't', mode: 'url', file: null, videoUrl: 'https://example.com/a.mp4' })).toEqual({})
  })
})
