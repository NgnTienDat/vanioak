import { test } from 'node:test'
import assert from 'node:assert/strict'
import { formatCount, formatTimestamp, relativeTime, severityGroups, lifecycleLabel, plainRowClick } from './presentation.ts'

test('compact presentation is UTC across offsets and keeps full seconds available without modifying input', () => {
  const value = '2026-10-30T13:06:54.668+07:00'
  assert.equal(formatTimestamp(value), '30 Oct, 06:06')
  assert.equal(formatTimestamp(value, true), '30 Oct 2026, 06:06:54')
  assert.equal(formatTimestamp('2026-10-30T06:06:54.668Z'), formatTimestamp(value))
  assert.equal(value, '2026-10-30T13:06:54.668+07:00')
  assert.equal(formatTimestamp('invalid'), 'Not recorded')
})
test('counts format only their presentation, including zero and large safe integers', () => {
  assert.equal(formatCount(0), '0')
  assert.equal(formatCount(12486), '12,486')
  assert.equal(formatCount(Number.MAX_SAFE_INTEGER), '9,007,199,254,740,991')
})
test('relative labels cover minute/hour/day boundaries and never call future timestamps ago', () => {
  const now = Date.parse('2026-10-30T06:06:00Z')
  assert.equal(relativeTime('2026-10-30T06:05:30Z', now), 'just now')
  assert.equal(relativeTime('2026-10-30T06:05:00Z', now), '1m ago')
  assert.equal(relativeTime('2026-10-30T05:06:00Z', now), '1h ago')
  assert.equal(relativeTime('2026-10-29T06:06:00Z', now), '1d ago')
  assert.equal(relativeTime('2026-10-30T06:08:00Z', now), 'in 2m')
  assert.equal(relativeTime('invalid', now), 'Not recorded')
})
test('stack/distribution groups preserve counts and derive Other without inventing INFO or WARN', () => {
  const values = Object.freeze({ total: 100, errors: 18, critical: 2 })
  assert.deepEqual(severityGroups(values).map(({ label, value }) => [label,value]), [['Other',80],['ERROR',18],['CRITICAL',2]])
  for (const counts of [values,{ total: 0, errors: 0, critical: 0 },{ total: 8, errors: 5, critical: 3 }]) {
    assert.equal(severityGroups(counts).reduce((sum,group) => sum + group.value, 0), counts.total)
    assert.ok(severityGroups(counts).every(group => group.value >= 0))
  }
  assert.deepEqual(values, { total: 100, errors: 18, critical: 2 })
})
test('timeline display labels retain unknown history types without synthesizing lifecycle events', () => {
  assert.equal(lifecycleLabel('OPENED'), 'Incident opened')
  assert.equal(lifecycleLabel('COUNT_UPDATED'), 'Occurrence count updated')
  assert.equal(lifecycleLabel('RESOLVED'), 'Incident resolved')
  assert.equal(lifecycleLabel('FUTURE_EVENT'), 'FUTURE_EVENT')
})
test('row navigation handles plain clicks and leaves modified/non-primary gestures to native links', () => {
  const plain = { button: 0, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false }
  assert.equal(plainRowClick(plain), true)
  for (const property of ['ctrlKey','metaKey','altKey','shiftKey']) assert.equal(plainRowClick({ ...plain, [property]: true }), false)
  assert.equal(plainRowClick({ ...plain, button: 1 }), false)
  assert.equal(plainRowClick({ ...plain, button: 2 }), false)
})
