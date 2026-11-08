const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { webcrypto } = require('node:crypto')
const ts = require('typescript')

function browser(storage = new Map(), calls = [], handler = null) {
  const exports = {}
  const source = fs.readFileSync('src/api/bookingAttempts.ts', 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText
  vm.runInNewContext(js, {
    exports, Date, setTimeout, TextEncoder, crypto: webcrypto,
    sessionStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) },
    require: () => ({ ApiError: class ApiError extends Error {}, USE_MOCK_TICKETING: false, request: async (...args) => { calls.push(args); if (handler) return handler(...args); return { stage: 'SEAT_HOLD', status: 'SUCCEEDED', sequence: 3 } } }),
  })
  return { api: exports, storage, calls }
}
test('duplicate start and refresh reuse the persisted attempt identity and click time', async () => {
  const b = browser()
  await b.api.startAttempt('alice', 'c1')
  await b.api.startAttempt('alice', 'c1')
  const refreshed = browser(b.storage, b.calls)
  await refreshed.api.startAttempt('alice', 'c1')
  assert.equal(b.calls[0][1], b.calls[1][1])
  assert.equal(b.calls[0][1], b.calls[2][1])
  assert.equal(JSON.stringify(b.calls[0][2]), JSON.stringify(b.calls[2][2]))
})
test('retries retain transitionId; explicit new journey gets another attemptId', async () => {
  const b = browser()
  await b.api.startAttempt('alice', 'c1')
  await b.api.reportStage('alice', 'c1', 'PAYMENT')
  await b.api.reportStage('alice', 'c1', 'PAYMENT')
  const transitions = b.calls.filter(call => call[0] === 'POST')
  assert.equal(transitions[0][2].transitionId, transitions[1][2].transitionId)
  const first = b.calls[0][1]
  b.api.clearAttempt('alice', 'c1')
  await b.api.startAttempt('alice', 'c1')
  assert.notEqual(first, b.calls[b.calls.length - 1][1])
})
test('users and concerts do not share an attempt identity', async () => {
  const b = browser()
  await b.api.startAttempt('alice', 'c1')
  await b.api.startAttempt('bob', 'c1')
  await b.api.startAttempt('alice', 'c2')
  assert.equal(new Set(b.calls.map(call => call[1])).size, 3)
})

test('disabled capability is explicit; lookup failures do not bypass attempts', async () => {
  const b = browser(new Map(), [], async () => ({ enabled: false }))
  assert.equal(await b.api.attemptsEnabled(), false)
  const broken = browser(new Map(), [], async () => { throw new Error('offline') })
  await assert.rejects(broken.api.attemptsEnabled(), /offline/)
})
test('lost response persists stage body across reload and recovery', async () => {
  const b = browser();await b.api.startAttempt('alice', 'c1')
  const offline = browser(b.storage, [], async () => { throw new Error('offline') })
  await assert.rejects(offline.api.reportStage('alice', 'c1', 'PAYMENT'), /offline/)
  assert.equal(offline.calls.length, 3)
  const restored = browser(b.storage)
  await restored.api.getAttempt('alice', 'c1')
  assert.equal(JSON.stringify(restored.calls[0][2]), JSON.stringify(offline.calls[0][2]))
  assert.equal(restored.calls[0][0], 'POST')
  assert.equal(restored.api.localAttempt('alice', 'c1').pending.length, 0)
})
test('seat-selection transient retry and concurrent calls share transition', async () => {
  const b = browser();await b.api.startAttempt('alice', 'c1')
  let failures = 1
  const flaky = browser(b.storage, [], async () => { if (failures-- > 0) throw new Error('offline'); return {} })
  await Promise.all([flaky.api.reportStage('alice', 'c1', 'SEAT_SELECTION'), flaky.api.reportStage('alice', 'c1', 'SEAT_SELECTION')])
  assert.equal(flaky.calls.length, 2)
  assert.equal(flaky.calls[0][2].transitionId, flaky.calls[1][2].transitionId)
})
test('captcha identity survives reload and changed input uses a new identity', async () => {
  const b = browser();await b.api.startAttempt('alice', 'c1')
  const id = b.api.operationTransition('alice', 'c1', 'captcha:challenge:answer')
  const restored = browser(b.storage)
  assert.equal(restored.api.operationTransition('alice', 'c1', 'captcha:challenge:answer'), id)
  assert.notEqual(restored.api.operationTransition('alice', 'c1', 'captcha:challenge:changed'), id)
})

test('captcha retries without a booking attempt retain identity without storing answer', async () => {
  const b = browser()
  const id = await b.api.captchaTransition('alice', 'c1', 'challenge', 'secret-answer')
  const restored = browser(b.storage)
  assert.equal(await restored.api.captchaTransition('alice', 'c1', 'challenge', 'secret-answer'), id)
  assert.notEqual(await restored.api.captchaTransition('alice', 'c1', 'challenge', 'other'), id)
  assert.notEqual(await restored.api.captchaTransition('bob', 'c1', 'challenge', 'secret-answer'), id)
  assert.equal(JSON.stringify([...b.storage]).includes('secret-answer'), false)
})
test('failed start retains identity and rejects instead of continuing', async () => {
  const b = browser(new Map(), [], async () => { throw new Error('start failed') })
  await assert.rejects(b.api.startAttempt('alice', 'c1'), /start failed/)
  await assert.rejects(b.api.startAttempt('alice', 'c1'), /start failed/)
  assert.equal(b.calls[0][1], b.calls[1][1])
  assert.equal(JSON.stringify(b.calls[0][2]), JSON.stringify(b.calls[1][2]))
})
