import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { reportDonationFailure } from '../api/donations'
import { isCancelCode, parseFailParams } from '../lib/donation'

/**
 * Toss sends the browser here when the payment failed or the buyer closed the window. The server closes the pending
 * order as CANCELED (window closed) or FAILED; no money was taken and nothing is added to any total.
 */
export default function DonationFailPage() {
  const { search } = useLocation()
  const params = useMemo(() => parseFailParams(search), [search])
  const [reportError, setReportError] = useState<string | null>(null)
  const started = useRef(false)
  const canceled = isCancelCode(params.code)

  useEffect(() => {
    if (!params.orderId || started.current) return
    started.current = true
    reportDonationFailure(params.orderId, params.code, params.message).catch((e: Error) => setReportError(e.message))
  }, [params])

  return (
    <section className="donation-result">
      <h1>{canceled ? '결제가 취소되었습니다' : '결제에 실패했습니다'}</h1>
      <p className="notice info">
        {canceled ? '결제를 취소했습니다. 후원은 적립되지 않았고 돈도 빠져나가지 않았습니다.' : params.message}
      </p>
      {!canceled && <p className="muted">오류 코드: {params.code}</p>}
      {reportError && <p className="field-error">주문 상태를 갱신하지 못했습니다: {reportError}</p>}
      <p>
        <Link to="/">&larr; 목록으로</Link>
      </p>
    </section>
  )
}
