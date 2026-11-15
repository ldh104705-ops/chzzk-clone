import { parseServerMessage, serializeClientMessage, type ClientMessage, type ServerMessage } from './signaling'

export interface SignalingSocket {
  send(message: ClientMessage): void
  close(): void
}

interface Handlers {
  onOpen(): void
  onMessage(message: ServerMessage): void
  /** Fired once when the socket closes for any reason (including failures to connect). */
  onClose(): void
}

const KEEPALIVE_MS = 25_000

/** Opens the signaling WebSocket, parses frames, and sends a periodic ping so idle proxies keep it open. */
export function openSignalingSocket(url: string, handlers: Handlers): SignalingSocket {
  const ws = new WebSocket(url)
  let ping: ReturnType<typeof setInterval> | undefined
  let closed = false

  const finish = () => {
    if (closed) return
    closed = true
    clearInterval(ping)
    handlers.onClose()
  }

  ws.onopen = () => {
    ping = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(serializeClientMessage({ type: 'ping' }))
    }, KEEPALIVE_MS)
    handlers.onOpen()
  }
  ws.onmessage = (event) => {
    if (typeof event.data !== 'string') return
    const message = parseServerMessage(event.data)
    if (message) handlers.onMessage(message)
  }
  ws.onclose = finish
  ws.onerror = () => {
    // A close event always follows an error; reconnect logic lives in onClose.
  }

  return {
    send(message) {
      if (ws.readyState === WebSocket.OPEN) ws.send(serializeClientMessage(message))
    },
    close() {
      handlers.onClose = () => {} // an intentional close must not trigger reconnects
      clearInterval(ping)
      ws.close()
    },
  }
}
