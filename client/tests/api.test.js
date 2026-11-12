import test from 'node:test'
import assert from 'node:assert/strict'
import { apiBaseFor, queuedMessage } from '../src/apiRouting.js'
import { apiJson, apiRequest, setAuthToken, hasAuthToken } from '../src/api.js'

test('only task creation and ID status route to Java', () => {
  assert.equal(apiBaseFor('/analysis/ai?id=1&goal=test'), '/business-api')
  assert.equal(apiBaseFor('/analysis/task-status?taskId=x'), '/business-api')
  for (const path of ['/media/upload-chunk', '/analysis/transcribe', '/analysis/follow-up',
    '/analysis/report', '/analysis/agent-plan', '/analysis/agent-tools', '/user/login',
    '/analysis/analysis-status', '/analysis/ai-extra']) {
    assert.equal(apiBaseFor(path), '/api', path)
  }
})

test('explicit Python rollback and custom bases preserve endpoint paths', () => {
  assert.equal(apiBaseFor('/analysis/ai', { analysisBackend: 'python' }), '/api')
  assert.equal(apiBaseFor('/analysis/task-status?taskId=x', {
    analysisBackend: 'python', pythonBase: 'https://python.example/api/'
  }), 'https://python.example/api')
  assert.equal(apiBaseFor('/analysis/ai', { businessBase: '/java/' }), '/java')
})

test('pending delivery is distinct from acknowledged and duplicate tasks', () => {
  assert.match(queuedMessage({ dispatchStatus: 'PENDING' }), /投递待确认/)
  assert.match(queuedMessage({}, true), /已存在/)
  assert.match(queuedMessage({}), /已排队/)
})

const storage = new Map()
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
}
globalThis.window = new EventTarget()

test('one authenticated Java request; timeout/network error never reposts to Python', async () => {
  const oldFetch = globalThis.fetch
  const calls = []
  setAuthToken('python-token')
  globalThis.fetch = async (url, options) => {
    calls.push(url)
    assert.equal(options.headers.get('Authorization'), 'Bearer python-token')
    throw new TypeError('connection lost after possible commit')
  }
  try {
    await assert.rejects(apiRequest('/analysis/ai?id=1', { method: 'POST' }), /connection lost/)
    assert.deepEqual(calls, ['/business-api/analysis/ai?id=1'])
  } finally { globalThis.fetch = oldFetch }
})

test('401 clears shared authentication and notifies the Vue application', async () => {
  const oldFetch = globalThis.fetch
  let expired = false
  const listener = () => { expired = true }
  window.addEventListener('auth-expired', listener)
  setAuthToken('expired')
  globalThis.fetch = async () => new Response('{}', { status: 401 })
  try {
    assert.equal((await apiRequest('/analysis/task-status?taskId=x')).status, 401)
    assert.equal(hasAuthToken(), false)
    assert.equal(expired, true)
  } finally {
    globalThis.fetch = oldFetch
    window.removeEventListener('auth-expired', listener)
  }
})

test('409 and PENDING responses are returned intact without retrying', async () => {
  const oldFetch = globalThis.fetch
  let calls = 0
  try {
    for (const [status, body] of [[409, { taskId: 'same-task' }], [202, { taskId: 'saved', dispatchStatus: 'PENDING' }]]) {
      globalThis.fetch = async () => { calls++; return Response.json(body, { status }) }
      const response = await apiRequest('/analysis/ai?id=1', { method: 'POST' })
      assert.equal(response.status, status)
      assert.deepEqual(await response.json(), body)
    }
    assert.equal(calls, 2)
  } finally { globalThis.fetch = oldFetch }
})

test('auth preserves business errors and explains offline proxy responses', async () => {
  const oldFetch = globalThis.fetch
  try {
    for (const [response, expected] of [
      [new Response(null, { status: 500 }), /后端服务暂不可用/],
      [new Response('<html>Bad Gateway</html>', { status: 502 }), /后端服务暂不可用/],
      [Response.json({ message: '账号或密码错误' }, { status: 401 }), /账号或密码错误/],
      [Response.json({ message: '该账号已存在' }, { status: 409 }), /该账号已存在/],
      [Response.json({ detail: [{ msg: '密码长度不足' }] }, { status: 422 }), /密码长度不足/],
      [new Response('<html>frontend fallback</html>'), /服务器响应格式异常/]
    ]) {
      globalThis.fetch = async () => response
      await assert.rejects(apiJson('/user/login', { method: 'POST' }), expected)
    }
    globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
    await assert.rejects(apiJson('/user/register', { method: 'POST' }), /无法连接服务/)
    globalThis.fetch = async () => Response.json({ code: 200, token: 'valid-token' })
    assert.deepEqual(await apiJson('/user/login', { method: 'POST' }), { code: 200, token: 'valid-token' })
  } finally { globalThis.fetch = oldFetch }
})

test('follow-up decodes JSON answers and exposes prerequisite errors as readable text', async () => {
  const oldFetch = globalThis.fetch
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/api/analysis/follow-up?id=1&question=test')
      assert.equal(options.method, 'POST')
      return Response.json('基于视频证据的回答')
    }
    assert.equal(await apiJson('/analysis/follow-up?id=1&question=test', { method: 'POST' }), '基于视频证据的回答')
    globalThis.fetch = async () => Response.json({ detail: '请先完成视频分析' }, { status: 409 })
    await assert.rejects(apiJson('/analysis/follow-up?id=1&question=test', { method: 'POST' }), /^Error: 请先完成视频分析$/)
  } finally { globalThis.fetch = oldFetch }
})
