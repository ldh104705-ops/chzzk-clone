import { apiRequest, jsonBody } from './http'

export interface PaymentConfig {
  enabled: boolean
  clientKey?: string
  minAmount?: number
  maxAmount?: number
  maxMessageLength?: number
}

export interface DonationOrder {
  orderId: string
  amount: number
  orderName: string
  customerKey: string
  successUrl: string
  failUrl: string
  broadcastId: number
  recipientNickname: string
}

export type DonationStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'CANCELED'

export interface DonationResult {
  orderId: string
  status: DonationStatus
  amount: number
  broadcastId: number | null
  recipientNickname: string
  message: string | null
  approvedAt: string | null
  /** true when the server found it already confirmed (a repeated confirm: nothing was added twice). */
  alreadyConfirmed: boolean
}

export interface DonationItem {
  id: number
  donorNickname: string
  amount: number
  message: string | null
  approvedAt: string
}

export interface DonationList {
  items: DonationItem[]
  totalAmount: number
  count: number
}

export async function getPaymentConfig(): Promise<PaymentConfig> {
  const res = await apiRequest('/api/payments/config')
  return res.json()
}

export async function createDonation(input: { broadcastId: number; amount: number; message: string }): Promise<DonationOrder> {
  const res = await apiRequest('/api/donations', { method: 'POST', ...jsonBody(input) })
  return res.json()
}

/** The server asks Toss to approve and verifies the answer; the browser's success page alone confirms nothing. */
export async function confirmDonation(input: { paymentKey: string; orderId: string; amount: number }): Promise<DonationResult> {
  const res = await apiRequest('/api/donations/confirm', { method: 'POST', ...jsonBody(input) })
  return res.json()
}

export async function reportDonationFailure(orderId: string, code: string, message: string): Promise<DonationResult> {
  const res = await apiRequest(`/api/donations/${encodeURIComponent(orderId)}/fail`, {
    method: 'POST',
    ...jsonBody({ code, message }),
  })
  return res.json()
}

export async function listDonations(broadcastId: number | string, limit = 30): Promise<DonationList> {
  const res = await apiRequest(`/api/broadcasts/${broadcastId}/donations?limit=${limit}`)
  return res.json()
}
