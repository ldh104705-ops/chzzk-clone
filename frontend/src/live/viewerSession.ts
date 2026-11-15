import { openSignalingSocket, type SignalingSocket } from './socket'
import type { ServerMessage } from './signaling'
import { signalingUrl } from './wsUrl'

export type ViewerState = 'connecting' | 'waiting' | 'live' | 'reconnecting' | 'ended' | 'error'

export interface ViewerCallbacks {
  onState(state: ViewerState, detail?: string): void
  onStream(stream: MediaStream): void
}

const RECONNECT_DELAY_MS = 2000

/** Viewer side: joins the signaling room, answers the broadcaster's offer and exposes the received stream. */
export class ViewerSession {
  private socket: SignalingSocket | null = null
  private pc: RTCPeerConnection | null = null
  private stopped = false
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined
  private queue: Promise<void> = Promise.resolve()

  constructor(
    private readonly broadcastId: number,
    private readonly iceServers: RTCIceServer[],
    private readonly callbacks: ViewerCallbacks,
  ) {}

  start(): void {
    this.callbacks.onState('connecting')
    this.connect()
  }

  stop(): void {
    this.stopped = true
    clearTimeout(this.reconnectTimer)
    this.socket?.close()
    this.socket = null
    this.closePeer()
  }

  private connect(): void {
    this.socket = openSignalingSocket(signalingUrl(this.broadcastId), {
      onOpen: () => this.socket?.send({ type: 'join', role: 'viewer' }),
      onMessage: (message) => {
        this.queue = this.queue.then(() => this.handle(message)).catch((e) => console.warn('signaling error', e))
      },
      // Queued so that a final message from the server (e.g. broadcast-ended) is handled before the close.
      onClose: () => {
        this.queue = this.queue.then(() => this.reconnectLater())
      },
    })
  }

  /** Re-joins after a dropped socket or failed media connection (a new viewer id; the broadcaster re-offers). */
  private reconnectLater(): void {
    if (this.stopped) return
    this.closePeer()
    this.callbacks.onState('reconnecting')
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => this.connect(), RECONNECT_DELAY_MS)
  }

  private closePeer(): void {
    this.pc?.close()
    this.pc = null
  }

  private async handle(message: ServerMessage): Promise<void> {
    switch (message.type) {
      case 'joined':
        this.callbacks.onState(message.broadcasterConnected === false ? 'waiting' : 'connecting')
        break
      case 'offer':
        await this.answer(message.sdp)
        break
      case 'ice-candidate':
        if (message.candidate) await this.pc?.addIceCandidate(message.candidate).catch(() => {})
        break
      case 'broadcast-ended':
        this.stopped = true
        this.closePeer()
        this.callbacks.onState('ended')
        break
      case 'error':
        if (message.code === 'BROADCAST_ENDED') {
          this.stopped = true
          this.callbacks.onState('ended')
        } else if (message.code === 'BROADCAST_NOT_LIVE' || message.code === 'NOT_FOUND' || message.code === 'NOT_LIVE') {
          this.stopped = true
          this.callbacks.onState('error', message.message)
        }
        break
      default:
        break
    }
  }

  private async answer(offer: RTCSessionDescriptionInit): Promise<void> {
    this.closePeer() // a new offer (e.g. broadcaster reconnected) replaces the old connection
    const pc = new RTCPeerConnection({ iceServers: this.iceServers })
    this.pc = pc

    pc.ontrack = (event) => {
      const [stream] = event.streams
      if (stream) {
        this.callbacks.onStream(stream)
        this.callbacks.onState('live')
      }
    }
    pc.onicecandidate = (event) => {
      if (event.candidate) this.socket?.send({ type: 'ice-candidate', candidate: event.candidate.toJSON() })
    }
    pc.onconnectionstatechange = () => {
      if (this.pc !== pc || pc.connectionState !== 'failed') return
      this.socket?.close() // intentional close: no automatic reconnect from the socket
      this.reconnectLater()
    }

    await pc.setRemoteDescription(offer)
    const answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    this.socket?.send({ type: 'answer', sdp: { type: answer.type, sdp: answer.sdp } })
  }
}
