import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createUserSchema, editUserSchema, editUserRequestSchema, userEditFields } from './userSchema.ts'

test('username uses actual nonblank/max100 contract, not email or demo strength rules', () => {
  for (const username of ['engineer', 'a', 'x'.repeat(100), ' user ']) assert.ok(createUserSchema.safeParse({ username, password: ' ' }).success)
  for (const username of ['', '   ', 'x'.repeat(101)]) assert.equal(createUserSchema.safeParse({ username, password: 'x' }).success, false)
  for (const password of ['', null, undefined]) assert.equal(createUserSchema.safeParse({ username: 'e', password }).success, false)
  for (const extra of [{ role: 'ADMIN' }, { status: 'DISABLED' }]) assert.equal(createUserSchema.safeParse({ username: 'e', password: 'x', ...extra }).success, false)
})
test('edit omits unchanged username and empty password and preserves every password character', () => {
  assert.deepEqual(userEditFields('e', { username: 'e', password: '' }), {})
  assert.deepEqual(userEditFields('e', { username: 'new', password: '' }), { username: 'new' })
  assert.deepEqual(userEditFields('e', { username: 'e', password: '  x\n ' }), { password: '  x\n ' })
  assert.ok(editUserSchema.safeParse({ username: 'e', password: '' }).success)
  for (const value of [{}, { username: null }, { password: '' }, { role: 'ADMIN' }, { status: 'ACTIVE' }]) assert.equal(editUserRequestSchema.safeParse(value).success, false)
  assert.ok(editUserRequestSchema.safeParse({ password: ' ' }).success)
})
