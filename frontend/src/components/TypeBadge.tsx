import type { BroadcastType } from '../api/broadcasts'

const LABELS: Record<BroadcastType, string> = {
  RECORDED: '녹화',
  LIVE: '라이브',
}

export default function TypeBadge({ type }: { type: BroadcastType }) {
  return <span className={`badge badge-${type.toLowerCase()}`}>{LABELS[type] ?? type}</span>
}
