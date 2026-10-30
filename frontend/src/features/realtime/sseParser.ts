import { invalidStream } from './realtime.types.ts'

// Bound raw pending block bytes, including comments/unknown fields, before decoding.
export const MAX_SSE_FRAME_BYTES = 1024 * 1024
export function createSseParser(event: (type: string, data: string) => void, block: () => void = () => {}) {
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })
  let line = '', type = '', data: string[] = [], bytes = 0, skipLf = false, firstLine = true
  function finishLine() {
    line += decoder.decode()
    if (firstLine) { line = line.replace(/^\uFEFF/, ''); firstLine = false }
    if (line === '') {
      if (data.length) event(type || 'message', data.join('\n'))
      type = ''; data = []; bytes = 0
      block()
    } else if (!line.startsWith(':')) {
      const index = line.indexOf(':')
      const field = index < 0 ? line : line.slice(0, index)
      const value = index < 0 ? '' : line.slice(index + 1).replace(/^ /, '')
      if (field === 'event') type = value
      if (field === 'data') data.push(value)
    }
    line = ''
  }
  return {
    push(chunk: Uint8Array) {
      try {
        let start = 0
        for (let i = 0; i < chunk.length; i++) {
          const byte = chunk[i]
          if (skipLf) {
            skipLf = false
            if (byte === 10) {
              if (bytes > 0 && ++bytes > MAX_SSE_FRAME_BYTES) throw invalidStream()
              start = i + 1; continue
            }
          }
          if (++bytes > MAX_SSE_FRAME_BYTES) throw invalidStream()
          if (byte === 10 || byte === 13) {
            line += decoder.decode(chunk.subarray(start, i), { stream: true })
            finishLine(); skipLf = byte === 13; start = i + 1
          }
        }
        line += decoder.decode(chunk.subarray(start), { stream: true })
      } catch { throw invalidStream() }
    },
    finish() {
      try {
        line += decoder.decode()
        if (bytes || line || data.length || type) throw invalidStream()
      } catch { throw invalidStream() }
    },
  }
}
