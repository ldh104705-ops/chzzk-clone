import { describe, expect, it } from 'vitest'
import {
  isCancelCode,
  MAX_DONATION,
  MIN_DONATION,
  parseAmountInput,
  parseFailParams,
  parseSuccessParams,
  validateDonationMessage,
} from './donation'

describe('parseAmountInput', () => {
  it('accepts whole won inside the limits, with or without thousands separators', () => {
    expect(parseAmountInput('1000')).toEqual({ ok: true, amount: 1000 })
    expect(parseAmountInput(' 5,000 ')).toEqual({ ok: true, amount: 5000 })
    expect(parseAmountInput(String(MAX_DONATION))).toEqual({ ok: true, amount: MAX_DONATION })
  })

  it('rejects empty, non-integer and non-numeric input', () => {
    for (const bad of ['', '   ', '1000.5', '1e4', '-5000', 'abc', '5 000', '0x10']) {
      expect(parseAmountInput(bad).ok, bad).toBe(false)
    }
  })

  it('rejects amounts below the minimum and above the maximum', () => {
    expect(parseAmountInput(String(MIN_DONATION - 1)).ok).toBe(false)
    expect(parseAmountInput('0').ok).toBe(false)
    expect(parseAmountInput(String(MAX_DONATION + 1)).ok).toBe(false)
    expect(parseAmountInput('99999999999999999999').ok).toBe(false)
  })
})

describe('validateDonationMessage', () => {
  it('allows empty and up to 100 characters, counting emoji as one', () => {
    expect(validateDonationMessage('')).toBeNull()
    expect(validateDonationMessage('가'.repeat(100))).toBeNull()
    expect(validateDonationMessage('😀'.repeat(100))).toBeNull()
    expect(validateDonationMessage('가'.repeat(101))).not.toBeNull()
  })
})

describe('parseSuccessParams', () => {
  it('reads paymentKey, orderId and a numeric amount', () => {
    expect(parseSuccessParams('?paymentKey=pk_1&orderId=donation_abc&amount=5000&paymentType=NORMAL')).toEqual({
      paymentKey: 'pk_1',
      orderId: 'donation_abc',
      amount: 5000,
    })
  })

  it('returns null when a value is missing or the amount is not an integer', () => {
    expect(parseSuccessParams('')).toBeNull()
    expect(parseSuccessParams('?orderId=o&amount=5000')).toBeNull()
    expect(parseSuccessParams('?paymentKey=k&amount=5000')).toBeNull()
    expect(parseSuccessParams('?paymentKey=k&orderId=o')).toBeNull()
    expect(parseSuccessParams('?paymentKey=k&orderId=o&amount=50.5')).toBeNull()
    expect(parseSuccessParams('?paymentKey=k&orderId=o&amount=abc')).toBeNull()
    expect(parseSuccessParams('?paymentKey=%20&orderId=o&amount=5000')).toBeNull()
  })
})

describe('parseFailParams and isCancelCode', () => {
  it('reads code, message and orderId (message is URL-decoded)', () => {
    expect(parseFailParams('?code=PAY_PROCESS_CANCELED&message=%EC%B7%A8%EC%86%8C&orderId=donation_x')).toEqual({
      code: 'PAY_PROCESS_CANCELED',
      message: '취소',
      orderId: 'donation_x',
    })
  })

  it('falls back to defaults when the query is empty', () => {
    expect(parseFailParams('')).toEqual({ code: 'UNKNOWN', message: '결제가 완료되지 않았습니다.', orderId: null })
  })

  it('treats both Toss cancel codes as cancellation only', () => {
    expect(isCancelCode('PAY_PROCESS_CANCELED')).toBe(true)
    expect(isCancelCode('USER_CANCEL')).toBe(true)
    expect(isCancelCode('REJECT_CARD_COMPANY')).toBe(false)
  })
})
