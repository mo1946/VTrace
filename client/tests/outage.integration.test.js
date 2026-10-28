import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { apiRequest, setAuthToken, clearAuthToken } from '../src/api.js'
import { queuedMessage } from '../src/apiRouting.js'

const enabled = process.env.VTRACE_EXPECT_MQ_FAILURE === '1' && Boolean(process.env.VTRACE_FRONTEND_URL)

test('real frontend preserves PENDING and duplicate task when Broker is stopped', { skip: !enabled, timeout: 30000 }, async t => {
  const fixture = JSON.parse(await readFile(process.env.VTRACE_TEST_FIXTURE_FILE, 'utf8'))
  const data = new Map()
  globalThis.localStorage = {
    getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key)
  }
  globalThis.window = new EventTarget()
  const originalFetch = globalThis.fetch
  const requests = []
  globalThis.fetch = (path, options) => {
    requests.push(String(path))
    return originalFetch(new URL(path, process.env.VTRACE_FRONTEND_URL), options)
  }
  t.after(() => { globalThis.fetch = originalFetch })
  setAuthToken(fixture.token)
  const path = '/analysis/ai?' + new URLSearchParams({ id: String(fixture.mediaId), goal: '总结视频 MQ故障 ' + Date.now() })
  const submitted = await apiRequest(path, { method: 'POST' })
  assert.equal(submitted.status, 202)
  const payload = await submitted.json()
  assert.equal(payload.dispatchStatus, 'PENDING')
  assert.match(queuedMessage(payload), /投递待确认/)
  const duplicate = await apiRequest(path, { method: 'POST' })
  assert.equal(duplicate.status, 409)
  assert.equal((await duplicate.json()).taskId, payload.taskId)
  const status = await apiRequest('/analysis/task-status?taskId=' + payload.taskId)
  assert.equal(status.status, 200)
  assert.equal((await status.json()).state, 'QUEUED')
  assert.ok(requests.every(url => url.startsWith('/business-api/')), 'No silent POST fallback to Python')
  setAuthToken(fixture.otherToken)
  assert.equal((await apiRequest('/analysis/task-status?taskId=' + payload.taskId)).status, 404)
  clearAuthToken()
  assert.equal((await apiRequest('/analysis/task-status?taskId=' + payload.taskId)).status, 401)
  const result = { taskId: payload.taskId, dispatchStatus: 'PENDING', state: 'QUEUED',
    duplicateStatus: 409, foreignStatus: 404, anonymousStatus: 401, silentFallback: false }
  if (process.env.VTRACE_FRONTEND_RESULT_FILE) await writeFile(process.env.VTRACE_FRONTEND_RESULT_FILE, JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result))
})
