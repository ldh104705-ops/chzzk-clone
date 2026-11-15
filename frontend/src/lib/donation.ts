/** Pure helpers for the donation UI (amount/message rules, Toss redirect query parsing). The server re-checks all of it. */

export const MIN_DONATION = 1_000
export const MAX_DONATION = 1_000_000
export const MAX_DONATION_MESSAGE = 100
export const DONATION_PRESETS = [1_000, 5_000, 10_000, 50_000] as const

export type AmountResult = { ok: true; amount: number } | { ok: false; error: string }

export function formatWon(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

/** Parses what the user typed ("5,000" is fine): whole won only, between MIN_DONATION and MAX_DONATION. */
export function parseAmountInput(raw: string): AmountResult {
  const text = raw.trim().replace(/,/g, '')
  if (text === '') return { ok: false, error: '후원 금액을 입력해 주세요.' }
  if (!/^\d+$/.test(text)) return { ok: false, error: '후원 금액은 정수(원)로 입력해 주세요.' }
  const amount = Number(text)
  if (!Number.isSafeInteger(amount) || amount < MIN_DONATION || amount > MAX_DONATION) {
    return { ok: false, error: `후원 금액은 ${formatWon(MIN_DONATION)} 이상 ${formatWon(MAX_DONATION)} 이하여야 합니다.` }
  }
  return { ok: true, amount }
}

/** Returns an error message, or null when the (optional) message is fine. */
export function validateDonationMessage(message: string): string | null {
  if ([...message.trim()].length > MAX_DONATION_MESSAGE) return `후원 메시지는 ${MAX_DONATION_MESSAGE}자 이하여야 합니다.`
  return null
}

export interface SuccessParams {
  paymentKey: string
  orderId: string
  amount: number
}

/**
 * Reads Toss's successUrl query (?paymentKey=...&orderId=...&amount=...). Returns null when anything is missing or
 * malformed. These values are only what the browser was told: the server verifies them with Toss before confirming.
 */
export function parseSuccessParams(search: string): SuccessParams | null {
  const q = new URLSearchParams(search)
  const paymentKey = q.get('paymentKey')?.trim() ?? ''
  const orderId = q.get('orderId')?.trim() ?? ''
  const amountText = q.get('amount')?.trim() ?? ''
  if (paymentKey === '' || orderId === '' || !/^\d+$/.test(amountText)) return null
  const amount = Number(amountText)
  if (!Number.isSafeInteger(amount)) return null
  return { paymentKey, orderId, amount }
}

export interface FailParams {
  code: string
  message: string
  orderId: string | null
}

/** Reads Toss's failUrl query (?code=...&message=...&orderId=...). Never fails: missing parts get defaults. */
export function parseFailParams(search: string): FailParams {
  const q = new URLSearchParams(search)
  const orderId = q.get('orderId')?.trim() ?? ''
  return {
    code: q.get('code')?.trim() || 'UNKNOWN',
    message: q.get('message')?.trim() || '결제가 완료되지 않았습니다.',
    orderId: orderId === '' ? null : orderId,
  }
}

/** Codes Toss uses when the buyer closes or cancels the payment window. */
export function isCancelCode(code: string): boolean {
  return code === 'PAY_PROCESS_CANCELED' || code === 'USER_CANCEL'
}
