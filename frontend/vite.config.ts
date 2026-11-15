/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In dev, proxy API calls to the Spring Boot backend so the browser stays same-origin.
// (/ws will be proxied here too once live streaming is added in step 2.)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
  },
})
