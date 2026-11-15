import type { BroadcastType } from '../api/broadcasts'

const LABELS: Record<BroadcastType, string> = {
  RECORDED: '녹화',
}

export default function TypeBadge({ type }: { type: BroadcastType }) {
  return <span className={`badge badge-${type.toLowerCase()}`}>{LABELS[type] ?? type}</span>
}
