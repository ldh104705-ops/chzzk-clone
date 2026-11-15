import { describe, expect, it } from 'vitest'
import {
  INSECURE_CONTEXT_MESSAGE,
  NO_MEDIA_API_MESSAGE,
  checkMediaSupport,
  describeMediaError,
  otherFacing,
} from './media'

describe('checkMediaSupport', () => {
  it('explains the HTTPS requirement on insecure pages', () => {
    const message = checkMediaSupport({ isSecureContext: false, mediaDevices: undefined })
    expect(message).toBe(INSECURE_CONTEXT_MESSAGE)
    expect(message).toContain('HTTPS')
  })

  it('reports a missing getUserMedia API on secure pages', () => {
    expect(checkMediaSupport({ isSecureContext: true, mediaDevices: undefined })).toBe(NO_MEDIA_API_MESSAGE)
  })

  it('returns null when the camera can be used', () => {
    expect(checkMediaSupport({ isSecureContext: true, mediaDevices: {} })).toBeNull()
  })
})

describe('describeMediaError', () => {
  const named = (name: string) => Object.assign(new Error('x'), { name })

  it('tells the user how to fix a denied permission', () => {
    expect(describeMediaError(named('NotAllowedError'))).toContain('권한이 거부')
  })

  it('distinguishes missing and busy devices', () => {
    expect(describeMediaError(named('NotFoundError'))).toContain('찾을 수 없습니다')
    expect(describeMediaError(named('NotReadableError'))).toContain('사용 중')
  })

  it('falls back to a generic message', () => {
    expect(describeMediaError(new Error('boom'))).toContain('boom')
    expect(describeMediaError('weird')).toContain('시작하지 못했습니다')
  })
})

describe('otherFacing', () => {
  it('toggles between front and back camera', () => {
    expect(otherFacing('user')).toBe('environment')
    expect(otherFacing('environment')).toBe('user')
  })
})
