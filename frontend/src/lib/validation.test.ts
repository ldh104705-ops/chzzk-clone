import { describe, expect, it } from 'vitest'
import { isHttpUrl, validateRecordedForm, validateSignupForm } from './validation'

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

describe('validateSignupForm', () => {
  it('accepts a valid form', () => {
    expect(validateSignupForm({ username: 'Alice_1', password: 'correct-horse-1', nickname: '앨리스' })).toEqual({})
  })

  it('reports each invalid field', () => {
    const errors = validateSignupForm({ username: 'a!', password: 'short', nickname: '   ' })
    expect(Object.keys(errors).sort()).toEqual(['nickname', 'password', 'username'])
  })

  it('rejects passwords beyond the 72 bytes BCrypt can use and over-long nicknames', () => {
    expect(validateSignupForm({ username: 'alice', password: '가'.repeat(25), nickname: 'a' }).password).toBeDefined()
    expect(validateSignupForm({ username: 'alice', password: 'x'.repeat(72), nickname: 'a' }).password).toBeUndefined()
    expect(validateSignupForm({ username: 'alice', password: 'x'.repeat(8), nickname: 'n'.repeat(21) }).nickname).toBeDefined()
  })
})
