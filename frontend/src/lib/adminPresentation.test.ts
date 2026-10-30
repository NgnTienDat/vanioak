import { test } from 'node:test'
import assert from 'node:assert/strict'
import { adminFeedback, applicationMenuKey, enabledFromSelect } from './adminPresentation.ts'

test('overflow keyboard traversal wraps and supports both endpoints without activating an action', () => {
  assert.equal(applicationMenuKey('ArrowDown', 0, 2), 1)
  assert.equal(applicationMenuKey('ArrowDown', 1, 2), 0)
  assert.equal(applicationMenuKey('ArrowUp', 0, 2), 1)
  assert.equal(applicationMenuKey('ArrowDown', -1, 2), 0)
  assert.equal(applicationMenuKey('Home', 1, 2), 0)
  assert.equal(applicationMenuKey('End', 0, 2), 1)
  for (const key of ['ArrowUp', 'ArrowDown', 'Home', 'End']) assert.equal(applicationMenuKey(key, 0, 1), 0)
})
test('Escape and Tab dismiss; activation stays with native buttons and empty menus cannot receive focus', () => {
  for (const key of ['Escape', 'Tab']) assert.equal(applicationMenuKey(key, 0, 2), 'close')
  for (const key of ['Enter', ' ', 'a', 'ArrowLeft']) assert.equal(applicationMenuKey(key, 0, 2), null)
  assert.equal(applicationMenuKey('ArrowDown', -1, 0), null)
})
test('Enabled select preserves actual booleans including false and boolean form defaults', () => {
  for (const value of [true, 'true']) assert.equal(enabledFromSelect(value), true)
  for (const value of [false, 'false']) assert.equal(enabledFromSelect(value), false)
  for (const value of ['', undefined, null, 'enabled', 0, 1]) assert.throws(() => enabledFromSelect(value))
})
test('only confirmed feedback gets success; uncertain and actionable notices remain persistent', () => {
  assert.deepEqual(adminFeedback(true, 'Account confirmed.', 'old notice'), { success: 'Account confirmed.', notice: '' })
  for (const notice of ['Write outcome unconfirmed.', 'Check metadata before another operation.', 'Password change cannot be verified.', 'Access denied.']) {
    assert.deepEqual(adminFeedback(false, 'Must not show success.', notice), { success: '', notice })
  }
  assert.deepEqual(adminFeedback(false, 'Must not show success.'), { success: '', notice: '' })
})
