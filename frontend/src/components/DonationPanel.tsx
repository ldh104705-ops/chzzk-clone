import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import {
  createDonation,
  getPaymentConfig,
  listDonations,
  reportDonationFailure,
  type DonationList,
  type PaymentConfig,
} from '../api/donations'
import { useAuth } from '../auth/AuthContext'
import {
  DONATION_PRESETS,
  formatWon,
  MAX_DONATION_MESSAGE,
  parseAmountInput,
  validateDonationMessage,
} from '../lib/donation'
import { formatDateTime } from '../lib/format'
import { loadTossPayments, type TossPaymentWindow } from '../lib/tossSdk'

/** Live broadcasts refresh the donation list this often (polling, like comments). */
const LIVE_POLL_MS = 4000

interface Props {
  broadcastId: number
  /** The broadcaster (recipient). Null for old broadcasts without an owner: nobody can receive donations there. */
  ownerId: number | null
  ownerNickname: string | null
  live: boolean
}

/**
 * Donation list + total (public) and the donation form (login). Paying happens in the Toss payment window; the
 * donation only counts after the server verified the payment (see DonationSuccessPage).
 */
export default function DonationPanel({ broadcastId, ownerId, ownerNickname, live }: Props) {
  const { user } = useAuth()
  const location = useLocation()
  const [config, setConfig] = useState<PaymentConfig | null>(null)
  const [list, setList] = useState<DonationList | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [amountText, setAmountText] = useState('5000')
  const [message, setMessage] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const paymentWindow = useRef<TossPaymentWindow | null>(null)

  const refresh = useCallback(async () => {
    try {
      setList(await listDonations(broadcastId))
      setListError(null)
    } catch (e) {
      setListError((e as Error).message)
    }
  }, [broadcastId])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    getPaymentConfig()
      .then(setConfig)
      .catch(() => setConfig({ enabled: false }))
  }, [])

  useEffect(() => {
    if (!live) return
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, LIVE_POLL_MS)
    return () => clearInterval(timer)
  }, [live, refresh])

  useEffect(() => () => void paymentWindow.current?.destroy().catch(() => undefined), [])

  const isOwner = user !== null && ownerId !== null && user.id === ownerId

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!config?.enabled || !config.clientKey) return
    const parsed = parseAmountInput(amountText)
    if (!parsed.ok) return setError(parsed.error)
    const messageError = validateDonationMessage(message)
    if (messageError) return setError(messageError)

    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      // 1) The server records a PENDING donation (recipient = the broadcaster) and returns the order to pay.
      const order = await createDonation({ broadcastId, amount: parsed.amount, message: message.trim() })
      // 2) Toss payment window (SDK v2, payment-window type).
      const TossPayments = await loadTossPayments()
      const widgets = TossPayments(config.clientKey).widgets({ customerKey: order.customerKey })
      await widgets.setAmount({ currency: 'KRW', value: order.amount })
      await paymentWindow.current?.destroy().catch(() => undefined)
      const window_ = await widgets.renderPaymentWindow({
        orderName: order.orderName,
        variantKey: { paymentMethod: 'DEFAULT', agreement: 'AGREEMENT' },
      })
      paymentWindow.current = window_
      window_.on('paymentRequest', async () => {
        try {
          // Toss redirects the browser to successUrl / failUrl; the success page asks the server to confirm.
          await widgets.requestPayment({
            orderId: order.orderId,
            orderName: order.orderName,
            successUrl: order.successUrl,
            failUrl: order.failUrl,
          })
        } catch (err) {
          const code = (err as { code?: string }).code ?? 'UNKNOWN'
          const text = (err as Error).message || '결제를 시작하지 못했습니다.'
          await reportDonationFailure(order.orderId, code, text).catch(() => undefined)
          setError(`결제가 완료되지 않았습니다: ${text}`)
        }
      })
      window_.on('cancel', async () => {
        await window_.destroy().catch(() => undefined)
        paymentWindow.current = null
        await reportDonationFailure(order.orderId, 'USER_CANCEL', '사용자가 결제창을 닫았습니다.').catch(() => undefined)
        setNotice('결제가 취소되었습니다. 후원은 적립되지 않았습니다.')
      })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  function renderForm() {
    if (config === null) return null
    if (!config.enabled) return <p className="notice info">결제 설정이 없어 후원을 사용할 수 없습니다</p>
    if (ownerId === null) return <p className="notice info">방송자 정보가 없는 방송에는 후원할 수 없습니다.</p>
    if (!user) {
      return (
        <p className="notice info">
          후원하려면 <Link to="/login" state={{ from: location.pathname }}>로그인</Link>해 주세요.
        </p>
      )
    }
    if (isOwner) return <p className="notice info">자신의 방송에는 후원할 수 없습니다.</p>
    return (
      <form onSubmit={onSubmit} className="donation-form">
        <p>
          후원 받는 사람: <strong>{ownerNickname ?? '방송자'}</strong> (방송자)
        </p>
        <div className="presets" role="group" aria-label="후원 금액 선택">
          {DONATION_PRESETS.map((p) => (
            <button key={p} type="button" className="button" onClick={() => setAmountText(String(p))} disabled={busy}>
              {formatWon(p)}
            </button>
          ))}
        </div>
        <label>
          금액(원){' '}
          <input
            value={amountText}
            onChange={(e) => setAmountText(e.target.value)}
            inputMode="numeric"
            aria-label="후원 금액(원)"
            placeholder="1,000 ~ 1,000,000"
          />
        </label>
        <textarea
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          maxLength={MAX_DONATION_MESSAGE}
          rows={2}
          placeholder="후원 메시지 (선택, 100자 이하)"
        />
        <button type="submit" className="button primary" disabled={busy}>
          {busy ? '결제창 준비 중...' : '후원하기'}
        </button>
        <p className="muted">테스트 결제입니다. 실제로 돈이 빠져나가지 않습니다.</p>
      </form>
    )
  }

  return (
    <section className="donations" aria-label="후원">
      <h2 className="section-title">
        후원 {list ? `(${list.count}건 · 합계 ${formatWon(list.totalAmount)})` : ''}
      </h2>
      {renderForm()}
      {error && <p className="field-error">{error}</p>}
      {notice && <p className="notice info">{notice}</p>}
      {listError && <p className="field-error">후원 내역을 불러오지 못했습니다: {listError}</p>}
      {list && list.items.length === 0 && <p className="state">아직 후원이 없습니다.</p>}
      <ul className="comment-list">
        {list?.items.map((d) => (
          <li key={d.id} className="comment">
            <div className="comment-head">
              <strong>{d.donorNickname}</strong>
              <span className="donation-amount">{formatWon(d.amount)}</span>
              <time dateTime={d.approvedAt}>{formatDateTime(d.approvedAt)}</time>
            </div>
            {d.message && <p className="comment-body">{d.message}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}
