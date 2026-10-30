import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { realpathSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import configuration from './vite.config.ts'
import type { ConfigEnv, UserConfig } from 'vite'
const configure = configuration as (env: ConfigEnv) => UserConfig
test('development config loads ignored local file without manual env, backend connection or server activation', () => {
  const original = process.cwd(), directory = mkdtempSync(join(tmpdir(),'vanioak-config-'))
  const previous = process.env.BACKEND_PROXY_TARGET
  const previousBase = process.env.VITE_API_BASE_URL
  try {
    process.chdir(directory); delete process.env.BACKEND_PROXY_TARGET; delete process.env.VITE_API_BASE_URL
    assert.throws(() => configure({ command: 'serve',mode: 'development' }),/BACKEND_PROXY_TARGET/)
    writeFileSync(join(directory,'.env.development.local'),'BACKEND_PROXY_TARGET=http://localhost:8080\nVITE_API_BASE_URL=/api/v1\n')
    const config = configure({ command: 'serve',mode: 'development' })
    const proxy = config.server?.proxy?.['/api/v1']
    assert.equal(typeof proxy === 'object' && proxy.target,'http://localhost:8080')
    process.env.VITE_API_BASE_URL = 'https://outside.test/api/v1'
    assert.throws(() => configure({ command: 'serve',mode: 'development' }),/same-origin/)
    delete process.env.VITE_API_BASE_URL
    process.env.BACKEND_PROXY_TARGET = 'http://user:secret@localhost:8080'
    assert.throws(() => configure({ command: 'serve',mode: 'development' }),error => error instanceof Error && !error.message.includes('secret'))
  } finally {
    process.chdir(original)
    if (previous === undefined) delete process.env.BACKEND_PROXY_TARGET; else process.env.BACKEND_PROXY_TARGET = previous
    if (previousBase === undefined) delete process.env.VITE_API_BASE_URL; else process.env.VITE_API_BASE_URL = previousBase
    assert.equal(dirname(realpathSync(directory)),realpathSync(tmpdir()))
    rmSync(directory,{ recursive: true })
  }
})
test('local env is ignored while example remains trackable', () => {
  const git = ['--no-optional-locks','-c','safe.directory=D:/Web System Development/vanioak']
  const result = execFileSync('git',[...git,'check-ignore','frontend/.env.development.local'],{ cwd: '..',encoding: 'utf8' })
  assert.match(result,/\.env\.development\.local/)
  assert.throws(() => execFileSync('git',[...git,'check-ignore','frontend/.env.example'],{ cwd: '..',stdio: 'pipe' }))
})
