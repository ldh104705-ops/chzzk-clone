/// <reference types="vitest/config" />
import basicSsl from '@vitejs/plugin-basic-ssl'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv, type ProxyOptions } from 'vite'

const BACKEND = 'http://localhost:8080'

/**
 * Drops the browser's Origin header before it reaches Spring. The phone opens the page on
 * https://<PC IP>:5173 while the backend sees plain http, so Spring would treat even same-origin POSTs and
 * WebSocket handshakes as cross-origin and reject them. Through this proxy the browser only ever talks to
 * the Vite server, so there is no cross-origin request to protect against.
 */
const dropOrigin: ProxyOptions['configure'] = (proxy) => {
  proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
  proxy.on('proxyReqWs', (proxyReq) => proxyReq.removeHeader('origin'))
}

// In dev, proxy API and signaling calls to the Spring Boot backend so the browser stays same-origin.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  // Phones only allow the camera on HTTPS: VITE_DEV_HTTPS=true serves the dev server with a self-signed certificate.
  const useHttps = env.VITE_DEV_HTTPS === 'true'
  // Extra Host headers the dev server accepts, e.g. a cloudflared tunnel: VITE_ALLOWED_HOSTS=abc.trycloudflare.com
  const allowedHosts = (env.VITE_ALLOWED_HOSTS ?? '')
    .split(',')
    .map((host) => host.trim())
    .filter(Boolean)

  return {
    plugins: [react(), ...(useHttps ? [basicSsl()] : [])],
    server: {
      host: true, // listen on the LAN (0.0.0.0) so a phone on the same Wi-Fi can reach https://<PC IP>:5173
      ...(allowedHosts.length > 0 ? { allowedHosts } : {}),
      proxy: {
        '/api': { target: BACKEND, configure: dropOrigin },
        '/ws': { target: BACKEND, ws: true, configure: dropOrigin },
      },
    },
    test: {
      environment: 'node',
    },
  }
})
