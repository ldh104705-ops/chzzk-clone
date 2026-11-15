import { PeerBook } from './peerBook'
import { openSignalingSocket, type SignalingSocket } from './socket'
import type { ServerMessage } from './signaling'
import { signalingUrl } from './wsUrl'

export type BroadcasterState = 'connecting' | 'live' | 'reconnecting' | 'ended' | 'error'

export interface BroadcasterCallbacks {
  onState(state: BroadcasterState, detail?: string): void
  onViewerCount(count: number): void
}

const RECONNECT_DELAY_MS = 2000

/**
 * Broadcaster side of the WebRTC mesh: one RTCPeerConnection per viewer, all sending the same camera stream.
 * Fine for a handful of viewers (each viewer costs the phone one more upload of the video).
 * The WebSocket is used only for signaling; if it drops the session reconnects with the same broadcaster key
 * (the server keeps the broadcast LIVE for a grace period) and re-negotiates with every waiting viewer.
 */
export class BroadcasterSession {
  private socket: SignalingSocket | null = null
  private readonly peers = new PeerBook<RTCPeerConnection>()
  private stopped = false
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined
  private queue: Promise<void> = Promise.resolve()

  constructor(
    private readonly broadcastId: number,
    private readonly broadcasterKey: string,
    private readonly iceServers: RTCIceServer[],
    private stream: MediaStream,
    private readonly callbacks: BroadcasterCallbacks,
  ) {}

  start(): void {
    this.callbacks.onState('connecting')
    this.connect()
  }

  /** Swaps the camera (e.g. front -> back) on every existing connection without renegotiating. */
  async replaceStream(next: MediaStream): Promise<void> {
    this.stream = next
    const tracks = next.getTracks()
    const jobs: Promise<void>[] = []
    this.peers.forEach((pc) => {
      for (const sender of pc.getSenders()) {
        const replacement = tracks.find((t) => t.kind === sender.track?.kind)
        if (replacement) jobs.push(sender.replaceTrack(replacement))
      }
    })
    await Promise.all(jobs)
  }

  /** Disconnects from the server and all viewers. Does not end the broadcast (call the REST end endpoint). */
  stop(): void {
    this.stopped = true
    clearTimeout(this.reconnectTimer)
    this.socket?.close()
    this.socket = null
    this.peers.closeAll()
  }

  private connect(): void {
    this.socket = openSignalingSocket(signalingUrl(this.broadcastId), {
      onOpen: () => this.socket?.send({ type: 'join', role: 'broadcaster', broadcasterKey: this.broadcasterKey }),
      onMessage: (message) => {
        // Handle messages strictly one after another: SDP/ICE handling is async and order matters.
        this.queue = this.queue.then(() => this.handle(message)).catch((e) => console.warn('signaling error', e))
      },
      // Queued so that a final message from the server (e.g. broadcast-ended) is handled before the close.
      onClose: () => {
        this.queue = this.queue.then(() => this.onSocketClosed())
      },
    })
  }

  private onSocketClosed(): void {
    if (this.stopped) return
    this.peers.closeAll()
    this.callbacks.onState('reconnecting')
    this.reconnectTimer = setTimeout(() => this.connect(), RECONNECT_DELAY_MS)
  }

  private async handle(message: ServerMessage): Promise<void> {
    switch (message.type) {
      case 'joined':
        this.callbacks.onState('live')
        this.callbacks.onViewerCount(message.viewerCount)
        break
      case 'viewer-joined':
        this.callbacks.onViewerCount(message.viewerCount)
        await this.offerTo(message.viewerId)
        break
      case 'viewer-left':
        this.peers.remove(message.viewerId)
        this.callbacks.onViewerCount(message.viewerCount)
        break
      case 'answer':
        await this.peers.get(message.from)?.setRemoteDescription(message.sdp)
        break
      case 'ice-candidate':
        if (message.candidate) {
          await this.peers.get(message.from)?.addIceCandidate(message.candidate).catch(() => {})
        }
        break
      case 'broadcast-ended':
        this.stopped = true
        this.peers.closeAll()
        this.callbacks.onState('ended')
        break
      case 'error':
        if (message.code === 'BROADCAST_ENDED') {
          this.stopped = true
          this.callbacks.onState('ended')
        } else if (message.code === 'FORBIDDEN' || message.code === 'NOT_FOUND') {
          this.stopped = true
          this.callbacks.onState('error', message.message)
        }
        break
      default:
        break
    }
  }

  private async offerTo(viewerId: string): Promise<void> {
    const pc = new RTCPeerConnection({ iceServers: this.iceServers })
    this.peers.set(viewerId, pc) // closes any earlier connection for the same viewer
    this.stream.getTracks().forEach((track) => pc.addTrack(track, this.stream))

    pc.onicecandidate = (event) => {
      if (event.candidate) this.socket?.send({ type: 'ice-candidate', to: viewerId, candidate: event.candidate.toJSON() })
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.peers.removeIfCurrent(viewerId, pc)
    }

    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    this.socket?.send({ type: 'offer', to: viewerId, sdp: { type: offer.type, sdp: offer.sdp } })
  }
}
