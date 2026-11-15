/**
 * Loader and minimal typings for the Toss Payments JavaScript SDK v2 (payment-window type: widgets() +
 * renderPaymentWindow() + requestPayment()). Docs: https://docs.tosspayments.com/sdk/v2/js/payment-window
 * The script is loaded from Toss's official URL, only when someone actually starts a donation.
 */
export const TOSS_SDK_URL = 'https://js.tosspayments.com/v2/standard'

export interface TossPaymentWindow {
  on(event: 'paymentRequest', callback: () => void | Promise<void>): void
  on(event: 'cancel', callback: () => void | Promise<void>): void
  destroy(): Promise<void>
}

export interface TossWidgets {
  setAmount(amount: { currency: 'KRW'; value: number }): Promise<void>
  renderPaymentWindow(params: {
    orderName: string
    variantKey?: { paymentMethod?: string; agreement?: string }
  }): Promise<TossPaymentWindow>
  requestPayment(params: { orderId: string; orderName: string; successUrl: string; failUrl: string }): Promise<void>
}

export interface TossPaymentsInstance {
  widgets(params: { customerKey: string }): TossWidgets
}

export type TossPaymentsFactory = (clientKey: string) => TossPaymentsInstance

declare global {
  interface Window {
    TossPayments?: TossPaymentsFactory
  }
}

let loading: Promise<TossPaymentsFactory> | null = null

export function loadTossPayments(): Promise<TossPaymentsFactory> {
  if (window.TossPayments) return Promise.resolve(window.TossPayments)
  if (loading) return loading
  loading = new Promise<TossPaymentsFactory>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = TOSS_SDK_URL
    script.async = true
    script.onload = () => {
      if (window.TossPayments) resolve(window.TossPayments)
      else reject(new Error('토스페이먼츠 SDK를 불러오지 못했습니다.'))
    }
    script.onerror = () => {
      loading = null // allow a retry
      script.remove()
      reject(new Error('토스페이먼츠 SDK를 불러오지 못했습니다. 네트워크를 확인해 주세요.'))
    }
    document.head.appendChild(script)
  })
  return loading
}
