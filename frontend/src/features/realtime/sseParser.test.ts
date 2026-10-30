import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSseParser, MAX_SSE_FRAME_BYTES } from './sseParser.ts'
import { decodeRealtimeEvent } from './realtime.types.ts'
import { ApiError } from '../../lib/apiError.ts'
const encoder = new TextEncoder()
const scope = { applicationId: '31801983-8baa-4b0a-ba77-8df1f212668b', environmentId: '41801983-8baa-4b0a-ba77-8df1f212668b' }
const id = '51801983-8baa-4b0a-ba77-8df1f212668b'

test('SSE parser preserves fragmented Unicode/BOM, CRLF and multiline data; comments are heartbeats', () => {
  const events: unknown[] = []; let blocks = 0
  const parser = createSseParser((type, data) => events.push([type, JSON.parse(data)]), () => blocks++)
  const bytes = encoder.encode('\uFEFF: heartbeat\r\n\r\nevent: log\r\ndata: {"message":\r\ndata: "Tiếng Việt 🌳"}\r\n\r\n')
  for (const byte of bytes) parser.push(Uint8Array.of(byte))
  parser.finish()
  assert.deepEqual(events, [['log', { message: 'Tiếng Việt 🌳' }]])
  assert.equal(blocks, 2)
})
test('CR, LF and CRLF frames dispatch only after a complete blank line; id/retry are ignored', () => {
  const events: unknown[] = []
  const parser = createSseParser((...args) => events.push(args))
  parser.push(encoder.encode('event: a\rdata: one\r\revent: b\ndata: two\n\nid: unused\r\nretry: 0\r\ndata: three\r\n'))
  assert.deepEqual(events, [['a','one'], ['b','two']])
  parser.push(encoder.encode('\r\n')); parser.finish()
  assert.deepEqual(events.at(-1), ['message', 'three'])
})
test('incomplete terminal block or invalid UTF8 fails without dispatching partial data', () => {
  for (const input of [encoder.encode('data: unfinished\n'), encoder.encode(': unterminated'), Uint8Array.of(0xc3, 0x28, 10, 10)]) {
    const events: unknown[] = []; const parser = createSseParser((...args) => events.push(args))
    assert.throws(() => { parser.push(input); parser.finish() }, ApiError)
    assert.equal(events.length, 0)
  }
  const parser = createSseParser(() => {})
  parser.push(Uint8Array.of(0xc3)); assert.throws(() => parser.finish(), ApiError)
})
test('pending memory is bounded for data, comments and ignored fields, not total stream length', () => {
  for (const prefix of ['data:', ':', 'unknown:']) {
    const parser = createSseParser(() => {})
    assert.throws(() => parser.push(encoder.encode(prefix + 'x'.repeat(MAX_SSE_FRAME_BYTES))), ApiError)
  }
  const crlf = createSseParser(() => {})
  assert.throws(() => crlf.push(encoder.encode('x\r\n'.repeat(Math.floor(MAX_SSE_FRAME_BYTES / 3) + 1))), ApiError)
  let count = 0; const parser = createSseParser(() => {}, () => count++)
  parser.push(new Uint8Array(MAX_SSE_FRAME_BYTES + 1).fill(10)); parser.finish()
  assert.equal(count, MAX_SSE_FRAME_BYTES + 1)
})
test('all four real event shapes decode without inventing identity on update/resolution', () => {
  const log = { event_id: id, application_id: scope.applicationId, environment_id: scope.environmentId,
    host_ip: 'host', level: 'INFO', message: 'message', timestamp: '2026-10-29T09:00:00Z', trace_id: '', metadata: {} }
  assert.equal(decodeRealtimeEvent('log', JSON.stringify(log), scope)?.type, 'log')
  assert.equal(decodeRealtimeEvent('incident.created', JSON.stringify({ incident_id: id, application_id: scope.applicationId,
    environment_id: scope.environmentId, level: 'ERROR', status: 'OPEN', message: 'message', error_count: 2 }), scope)?.type, 'incident.created')
  const updated = decodeRealtimeEvent('incident.updated', JSON.stringify({ incident_id: id, error_count: 3, last_seen_at: log.timestamp }), scope)!
  const resolved = decodeRealtimeEvent('incident.resolved', JSON.stringify({ incident_id: id, status: 'RESOLVED', resolved_at: log.timestamp }), scope)!
  assert.ok(!('application_id' in updated.data)); assert.ok(!('environment_id' in resolved.data))
})
test('unknown events are ignored, malformed known payloads/foreign scopes never become valid events', () => {
  assert.equal(decodeRealtimeEvent('future.event', 'not JSON', scope), null)
  for (const [type, data] of [['log', '{}'], ['incident.updated', '{'],
    ['incident.updated', JSON.stringify({ incident_id: id, error_count: -1, last_seen_at: '2026-10-29T09:00:00Z' })],
    ['incident.resolved', JSON.stringify({ incident_id: id, status: 'OPEN', resolved_at: '2026-10-29T09:00:00Z' })],
    ['incident.created', JSON.stringify({ incident_id: id, application_id: id, environment_id: scope.environmentId,
      level: 'ERROR', status: 'OPEN', message: 'message', error_count: 1 })]]) {
    assert.throws(() => decodeRealtimeEvent(type, data, scope), error => error instanceof ApiError && !error.message.includes(data))
  }
})
