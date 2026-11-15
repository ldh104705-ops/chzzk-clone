import { useEffect, useRef, useState } from 'react'
import { getIceServers, type Broadcast } from '../api/broadcasts'
import { ViewerSession, type ViewerState } from '../live/viewerSession'

const OVERLAY_TEXT: Record<ViewerState, string | null> = {
  connecting: '방송에 연결하는 중...',
  waiting: '방송자의 연결을 기다리는 중...',
  live: null,
  reconnecting: '연결이 끊겨 다시 연결하는 중...',
  ended: '방송이 종료되었습니다.',
  error: null,
}

/** Watches a live broadcast over WebRTC. Starts muted because browsers block autoplay with sound. */
export default function LiveViewer({ broadcast, onEnded }: { broadcast: Broadcast; onEnded?: () => void }) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const initiallyEnded = broadcast.status === 'ENDED'
  const [state, setState] = useState<ViewerState>(initiallyEnded ? 'ended' : 'connecting')
  const [detail, setDetail] = useState<string | undefined>()
  const [muted, setMuted] = useState(true)

  const onEndedRef = useRef(onEnded)
  onEndedRef.current = onEnded

  useEffect(() => {
    if (initiallyEnded) return
    let session: ViewerSession | null = null
    let cancelled = false
    getIceServers().then((iceServers) => {
      if (cancelled) return
      session = new ViewerSession(broadcast.id, iceServers, {
        onState: (s, d) => {
          setState(s)
          setDetail(d)
          if (s === 'ended') onEndedRef.current?.()
        },
        onStream: (stream) => {
          const video = videoRef.current
          if (!video) return
          video.srcObject = stream
          video.play().catch(() => {}) // muted autoplay is allowed; ignore interrupted play() promises
        },
      })
      session.start()
    })
    return () => {
      cancelled = true
      session?.stop()
    }
  }, [broadcast.id, initiallyEnded])

  function unmute() {
    const video = videoRef.current
    if (!video) return
    video.muted = false
    setMuted(false)
    video.play().catch(() => {})
  }

  const overlay = state === 'error' ? (detail ?? '방송을 시청할 수 없습니다.') : OVERLAY_TEXT[state]
  return (
    <>
      <div className="video-box">
        <video ref={videoRef} autoPlay playsInline muted={muted} />
        {overlay && <div className="video-overlay">{overlay}</div>}
      </div>
      <div className="controls">
        {state === 'live' && muted && (
          <button type="button" className="button primary" onClick={unmute}>
            소리 켜기
          </button>
        )}
        {state === 'ended' && <span className="state">방송이 종료되어 더 이상 송출되지 않습니다.</span>}
      </div>
    </>
  )
}
