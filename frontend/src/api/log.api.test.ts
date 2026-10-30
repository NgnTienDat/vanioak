import { test } from 'node:test'
import assert from 'node:assert/strict'
import axios from 'axios'
import { createLogApi } from './log.api.ts'
import { ApiError } from '../lib/apiError.ts'
const application_id = '31801983-8baa-4b0a-ba77-8df1f212668b'
const environment_id = '41801983-8baa-4b0a-ba77-8df1f212668b'
const params = { application_id, environment_id, from: '2026-10-28T00:00:00.000Z', to: '2026-10-29T00:00:00.000Z' }
const row = { event_id: '51801983-8baa-4b0a-ba77-8df1f212668b', application_id, environment_id, host_ip: '127.0.0.1',
  level: 'ERROR', message: '<script>not markup</script>', timestamp: params.from, trace_id: '',
  received_at: params.from, processed_at: params.from, metadata: { nested: { service: null } } }

function apiFor(data: unknown) {
  return createLogApi(axios.create({ adapter: async config => ({ config, status: 200, statusText: 'OK', headers: {}, data }) }))
}
const envelope = (data: unknown) => ({ success: true, message: 'Success', data })

test('log API maps only documented filters/cursor to GET and decodes actual optional metadata', async () => {
  const abort = new AbortController()
  const api = createLogApi(axios.create({ adapter: async config => {
    assert.equal(config.method, 'get'); assert.equal(config.url, '/logs'); assert.equal(config.signal, abort.signal)
    assert.deepEqual(config.params, { ...params, level: 'ERROR', trace_id: 'trace+=', message: 'timeout', cursor: 'opaque+=', limit: 50 })
    return { config, status: 200, statusText: 'OK', headers: {}, data: envelope({ items: [row], next_cursor: 'next' }) }
  } }))
  assert.deepEqual(await api.list({ ...params, level: 'ERROR', trace_id: 'trace+=', message: 'timeout', cursor: 'opaque+=' }, abort.signal), { items: [row], next_cursor: 'next' })
  const withoutMetadata: Partial<typeof row> = { ...row }
  delete withoutMetadata.metadata
  assert.deepEqual((await apiFor(envelope({ items: [withoutMetadata], next_cursor: null })).list(params)).items[0], withoutMetadata)
})
test('ALL environments omits environment filter and keeps arbitrary server text as text data', async () => {
  const api = createLogApi(axios.create({ adapter: async config => {
    assert.equal(config.params.environment_id, undefined)
    assert.ok(!axios.getUri(config).includes('environment_id='))
    return { config, status: 200, statusText: 'OK', headers: {}, data: envelope({ items: [row], next_cursor: null }) }
  } }))
  assert.equal((await api.list({ ...params, environment_id: undefined })).items[0].message, row.message)
})
test('malformed envelopes, DTOs, cursors and out-of-scope log data fail closed', async () => {
  const mutations = [ { level: 'DEBUG' }, { host_ip: null }, { trace_id: null }, { metadata: [] },
    { timestamp: params.to }, { timestamp: 'invalid' }, { event_id: 'fake' }, { received_at: null },
    { application_id: environment_id }, { environment_id: application_id } ]
  for (const change of mutations) await assert.rejects(apiFor(envelope({ items: [{ ...row, ...change }], next_cursor: null })).list(params), ApiError)
  for (const body of [{ success: false, message: 'secret' }, {}, envelope({ items: [row], next_cursor: '' }), envelope({ items: [], next_cursor: 2 })]) {
    await assert.rejects(apiFor(body).list(params), error => error instanceof ApiError && !error.message.includes('secret'))
  }
})
test('invalid ranges, unsupported levels, byte limits and fabricated scope never send a request', async () => {
  let calls = 0
  const api = createLogApi(axios.create({ adapter: async () => { calls++; throw new Error('Should not send') } }))
  for (const change of [{ application_id: 'ALL' }, { environment_id: 'ALL' }, { limit: 101 }, { from: params.to },
    { from: '2026-10-01T00:00:00.000Z' }, { level: 'DEBUG' }, { trace_id: 'é'.repeat(513) }, { message: 'x'.repeat(4097) }]) {
    await assert.rejects(api.list({ ...params, ...change } as typeof params), ApiError)
  }
  assert.equal(calls, 0)
})
test('aborted log response cannot be delivered even when the adapter ignores cancellation', async () => {
  let deliver!: () => void
  const controller = new AbortController()
  const api = createLogApi(axios.create({ adapter: config => new Promise(resolve => {
    deliver = () => resolve({ config, status: 200, statusText: 'OK', headers: {}, data: envelope({ items: [row], next_cursor: null }) })
  }) }))
  const request = api.list(params, controller.signal)
  controller.abort(); deliver()
  await assert.rejects(request, error => axios.isCancel(error))
})
