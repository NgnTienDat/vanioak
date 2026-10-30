import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loginSchema } from './loginSchema.ts'
test('login schema preserves username/password semantics without email rules or trimming', () => {
  assert.equal(loginSchema.safeParse({ username: '',password: 'x' }).success,false)
  assert.equal(loginSchema.safeParse({ username: 'x'.repeat(101),password: 'x' }).success,false)
  assert.equal(loginSchema.safeParse({ username: 'plain-name',password: '' }).success,false)
  assert.deepEqual(loginSchema.parse({ username: 'plain-name',password: ' space ' }),{ username: 'plain-name',password: ' space ' })
})
