import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { confirmDonation, type DonationResult } from '../api/donations'
import { formatWon, parseSuccessParams } from '../lib/donation'
import { formatDateTime } from '../lib/format'

type State = { kind: 'loading' } | { kind: 'done'; result: DonationResult } | { kind: 'error'; message: string }

/**
 * Toss sends the browser here after the buyer authenticated the payment. This page confirms NOTHING by itself: it
 * hands paymentKey/orderId/amount to the server, which asks Toss to approve, checks the answer against the stored
 * order and only then confirms. Reloading the page repeats the same request; the server answers it idempotently.
 */
export default function DonationSuccessPage() {
  const { search } = useLocation()
  const params = useMemo(() => parseSuccessParams(search), [search])
  const [state, setState] = useState<State>(params ? { kind: 'loading' } : { kind: 'error', message: '결제 결과 정보가 올바르지 않습니다.' })
  const started = useRef(false)

  useEffect(() => {
    if (!params || started.current) return
    started.current = true
    confirmDonation(params)
      .then((result) => setState({ kind: 'done', result }))
      .catch((e: Error) => setState({ kind: 'error', message: e.message }))
  }, [params])

  return (
    <section className="donation-result">
      <h1>후원 결과</h1>
      {state.kind === 'loading' && <p className="state">서버에서 결제를 확인하는 중입니다...</p>}
      {state.kind === 'error' && (
        <>
          <p className="state error">후원을 확정하지 못했습니다: {state.message}</p>
          <p className="muted">실패한 경우 후원은 적립되지 않았습니다. 잠시 뒤 이 페이지를 새로고침하면 같은 요청을 다시 확인합니다.</p>
        </>
      )}
      {state.kind === 'done' && (
        <>
          <p className="notice info">
            {state.result.alreadyConfirmed
              ? `이미 확정된 후원입니다. 중복 적립은 되지 않았습니다. (${formatWon(state.result.amount)})`
              : `${state.result.recipientNickname}님에게 ${formatWon(state.result.amount)} 후원이 확정되었습니다.`}
          </p>
          {state.result.message && <p className="comment-body">“{state.result.message}”</p>}
          {state.result.approvedAt && <p className="muted">승인 시각: {formatDateTime(state.result.approvedAt)}</p>}
        </>
      )}
      <p>
        {state.kind === 'done' && state.result.broadcastId !== null ? (
          <Link to={`/broadcasts/${state.result.broadcastId}`}>&larr; 방송으로 돌아가기</Link>
        ) : (
          <Link to="/">&larr; 목록으로</Link>
        )}
      </p>
    </section>
  )
}
