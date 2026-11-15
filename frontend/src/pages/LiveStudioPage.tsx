import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { endLiveBroadcast, getBroadcast, getIceServers, type Broadcast } from '../api/broadcasts'
import { BroadcasterSession, type BroadcasterState } from '../live/broadcasterSession'
import { clearBroadcasterKey, loadBroadcasterKey } from '../live/keyStore'
import { checkMediaSupport, describeMediaError, otherFacing, type Facing } from '../live/media'

type Phase = 'preview' | BroadcasterState

function stopStream(stream: MediaStream | null) {
  stream?.getTracks().forEach((t) => t.stop())
}

/** Broadcaster studio: camera preview, front/back switch, start/end buttons and the viewer count. */
export default function LiveStudioPage() {
  const { id } = useParams<{ id: string }>()
  const broadcastId = Number(id)
  const [broadcast, setBroadcast] = useState<Broadcast | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [mediaError, setMediaError] = useState<string | null>(() =>
    checkMediaSupport({ isSecureContext: window.isSecureContext, mediaDevices: navigator.mediaDevices }),
  )
  const [switchError, setSwitchError] = useState<string | null>(null)
  const [facing, setFacing] = useState<Facing>('user')
  const [phase, setPhase] = useState<Phase>('preview')
  const [detail, setDetail] = useState<string | undefined>()
  const [viewerCount, setViewerCount] = useState(0)
  const [busy, setBusy] = useState(false)
  const [hasStream, setHasStream] = useState(false)

  const videoRef = useRef<HTMLVideoElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const sessionRef = useRef<BroadcasterSession | null>(null)
  const key = Number.isFinite(broadcastId) ? loadBroadcasterKey(broadcastId) : null

  useEffect(() => {
    if (!id) return
    getBroadcast(id)
      .then(setBroadcast)
      .catch((e: Error) => setLoadError(e.message))
  }, [id])

  // Open the camera + microphone for the preview (front camera first).
  useEffect(() => {
    if (mediaError || !key) return
    let cancelled = false
    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'user' }, audio: true })
      .then((stream) => {
        if (cancelled) return stopStream(stream)
        streamRef.current = stream
        if (videoRef.current) videoRef.current.srcObject = stream
        setHasStream(true)
      })
      .catch((e) => !cancelled && setMediaError(describeMediaError(e)))
    return () => {
      cancelled = true
      sessionRef.current?.stop()
      sessionRef.current = null
      stopStream(streamRef.current)
      streamRef.current = null
    }
  }, [mediaError, key])

  const switchCamera = useCallback(async () => {
    const current = streamRef.current
    if (!current) return
    const next = otherFacing(facing)
    setBusy(true)
    setSwitchError(null)
    try {
      // Release the old camera first: many phones cannot open two cameras at once.
      current.getVideoTracks().forEach((t) => t.stop())
      const video = await navigator.mediaDevices.getUserMedia({ video: { facingMode: next }, audio: false })
      const stream = new MediaStream([...video.getVideoTracks(), ...current.getAudioTracks()])
      streamRef.current = stream
      if (videoRef.current) videoRef.current.srcObject = stream
      await sessionRef.current?.replaceStream(stream) // keeps existing viewers connected
      setFacing(next)
    } catch (e) {
      setSwitchError(describeMediaError(e)) // keep the session running; only the switch failed
    } finally {
      setBusy(false)
    }
  }, [facing])

  async function startBroadcast() {
    const stream = streamRef.current
    if (!stream || !key || sessionRef.current) return
    setBusy(true)
    const iceServers = await getIceServers()
    const session = new BroadcasterSession(broadcastId, key, iceServers, stream, {
      onState: (state, d) => {
        setPhase(state)
        setDetail(d)
        if (state === 'ended') finishLocally()
      },
      onViewerCount: setViewerCount,
    })
    sessionRef.current = session
    session.start()
    setBusy(false)
  }

  function finishLocally() {
    sessionRef.current?.stop()
    sessionRef.current = null
    stopStream(streamRef.current)
    streamRef.current = null
    setHasStream(false)
    clearBroadcasterKey(broadcastId)
    setViewerCount(0)
  }

  async function endBroadcast() {
    if (!key) return
    setBusy(true)
    try {
      await endLiveBroadcast(broadcastId, key) // the server also tells every viewer
      setPhase('ended')
      finishLocally()
    } catch (e) {
      setDetail((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  if (loadError) return <p className="state error">{loadError}</p>
  if (!broadcast) return <p className="state">불러오는 중...</p>

  if (!key && phase !== 'ended') {
    return (
      <>
        <h1>{broadcast.title}</h1>
        <p className="notice">
          이 브라우저 탭에는 이 방송의 방송자 키가 없어 송출할 수 없습니다. (키는 방송을 만든 탭의 sessionStorage에만 저장됩니다.)
          새 라이브 방송을 만들어 주세요.
        </p>
        <p>
          <Link to="/live/new">새 라이브 방송 만들기</Link> · <Link to={`/broadcasts/${broadcast.id}`}>시청 화면 보기</Link>
        </p>
      </>
    )
  }

  const streaming = phase === 'connecting' || phase === 'live' || phase === 'reconnecting'
  const ended = phase === 'ended'
  return (
    <>
      <p>
        <Link to="/">&larr; 목록으로</Link>
      </p>
      <h1>{broadcast.title}</h1>

      {mediaError && <p className="notice">{mediaError}</p>}
      {switchError && <p className="notice">{switchError}</p>}

      <div className="video-box">
        <video ref={videoRef} autoPlay playsInline muted />
        {ended && <div className="video-overlay">방송이 종료되었습니다.</div>}
      </div>

      <div className="controls">
        <span className={`badge status-${phase === 'live' ? 'live' : ended ? 'ended' : 'ready'}`}>
          {phase === 'preview' && '미리보기'}
          {phase === 'connecting' && '연결 중'}
          {phase === 'live' && '방송 중'}
          {phase === 'reconnecting' && '재연결 중'}
          {phase === 'error' && '오류'}
          {ended && '종료'}
        </span>
        {streaming && <span>시청자 {viewerCount}명</span>}
        {detail && <span className="field-error">{detail}</span>}
      </div>

      {!ended && (
        <div className="controls">
          <button type="button" className="button" onClick={switchCamera} disabled={!hasStream || busy}>
            카메라 전환 ({facing === 'user' ? '전면 → 후면' : '후면 → 전면'})
          </button>
          {!streaming && (
            <button type="button" className="button primary" onClick={startBroadcast} disabled={!hasStream || busy}>
              방송 시작
            </button>
          )}
          {streaming && (
            <button type="button" className="button danger" onClick={endBroadcast} disabled={busy}>
              방송 종료
            </button>
          )}
          <Link to={`/broadcasts/${broadcast.id}`} target="_blank" className="button">
            시청 화면 열기
          </Link>
        </div>
      )}
      {ended && (
        <p>
          <Link to="/">목록으로 돌아가기</Link>
        </p>
      )}
    </>
  )
}
