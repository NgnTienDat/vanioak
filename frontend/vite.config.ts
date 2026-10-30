import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), ['BACKEND_', 'VITE_API_BASE_URL'])
  const base = process.env.VITE_API_BASE_URL ?? env.VITE_API_BASE_URL ?? '/api/v1'
  if (base !== '/api/v1' && base !== '/api/v1/') throw new Error('VITE_API_BASE_URL must be the same-origin /api/v1 prefix.')
  const target = process.env.BACKEND_PROXY_TARGET ?? env.BACKEND_PROXY_TARGET
  if (command === 'serve' && !target) throw new Error('Set BACKEND_PROXY_TARGET to an explicitly approved development backend. No fixture default is configured.')
  if (target) {
    let url: URL
    try { url = new URL(target) } catch { throw new Error('BACKEND_PROXY_TARGET must be a valid approved HTTP(S) origin.') }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
      throw new Error('BACKEND_PROXY_TARGET must be an HTTP(S) origin without credentials, query or path.')
    }
  }
  return {
    plugins: [react()],
    server: { proxy: target ? { '/api/v1': { target, changeOrigin: true } } : undefined },
  }
})
