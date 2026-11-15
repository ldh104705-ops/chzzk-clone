import type { BroadcastStatus } from '../api/broadcasts'

const LABELS: Record<BroadcastStatus, string> = {
  READY: '준비 중',
  LIVE: '방송 중',
  ENDED: '종료',
}

export default function StatusBadge({ status }: { status: BroadcastStatus }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{LABELS[status] ?? status}</span>
}
