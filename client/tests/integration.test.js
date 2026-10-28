import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { apiRequest, setAuthToken } from '../src/api.js'

const enabled = Boolean(process.env.VTRACE_FRONTEND_URL && process.env.VTRACE_VIDEO_FILE)
const nativeFetch = globalThis.fetch
const calls = []
const storage = new Map()
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key)
}
globalThis.window = new EventTarget()

test('real frontend client -> Vite -> Java -> MQ -> Python Worker -> report and follow-up', { skip: !enabled, timeout: 240000 }, async t => {
  const base = process.env.VTRACE_FRONTEND_URL
  const asrFixture = process.env.VTRACE_ASR_FIXTURE_URL
  const workerLogPath = process.env.VTRACE_WORKER_LOG
  assert.ok(asrFixture && workerLogPath, 'Supply isolated ASR fixture URL and original Worker log')
  await nativeFetch(asrFixture + '/fixture/reset', { method: 'POST' })
  t.after(() => nativeFetch(asrFixture + '/fixture/reset', { method: 'POST' }))
  globalThis.fetch = async (url, options) => {
    calls.push(String(url))
    return nativeFetch(new URL(url, base), options)
  }
  t.after(() => { globalThis.fetch = nativeFetch })
  const username = 'vue' + Date.now()
  const password = 'test-password-only'
  async function json(response, status = 200) {
    assert.equal(response.status, status, await response.clone().text())
    return response.json()
  }
  const user = await json(await apiRequest('/user/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, nickname: '前端联调用户' })
  }))
  setAuthToken(user.token)
  const video = await readFile(process.env.VTRACE_VIDEO_FILE)
  async function upload(filename) {
    const uploadId = await json(await apiRequest('/media/init-upload?' + new URLSearchParams({ filename, totalChunks: '2' }), { method: 'POST' }))
    assert.deepEqual(await json(await apiRequest('/media/upload-status?uploadId=' + uploadId)), [])
    for (let index = 0; index < 2; index++) {
      const data = new FormData()
      data.set('uploadId', uploadId); data.set('chunkIndex', String(index)); data.set('totalChunks', '2')
      const middle = Math.ceil(video.length / 2)
      data.set('file', new Blob([index ? video.subarray(middle) : video.subarray(0, middle)], { type: 'video/mp4' }), filename)
      await json(await apiRequest('/media/upload-chunk', { method: 'POST', body: data }))
      assert.deepEqual(await json(await apiRequest('/media/upload-status?uploadId=' + uploadId)), index ? [0, 1] : [0])
    }
    return json(await apiRequest('/media/complete-upload?uploadId=' + uploadId, { method: 'POST' }))
  }
  const uploaded = await upload('integration.mp4')
  assert.equal(uploaded.deduplicated, false)
  const duplicateUpload = await upload('integration-again.mp4')
  assert.equal(duplicateUpload.deduplicated, true)
  assert.equal(duplicateUpload.mediaId, uploaded.mediaId)
  const id = uploaded.mediaId
  const goal = '概括视频主要内容 ' + Date.now()
  const submitPath = '/analysis/ai?' + new URLSearchParams({ id: String(id), goal })
  const submissions = await Promise.all([apiRequest(submitPath, { method: 'POST' }), apiRequest(submitPath, { method: 'POST' })])
  assert.deepEqual(submissions.map(x => x.status).sort(), [202, 409])
  const accepted = await submissions.find(x => x.status === 202).json()
  assert.equal(accepted.dispatchStatus, 'SENT')
  async function waitFor(taskId, terminal) {
    const deadline = Date.now() + 170000
    while (Date.now() < deadline) {
      const status = await json(await apiRequest('/analysis/task-status?taskId=' + taskId))
      if (status.state === terminal) return status
      if (terminal !== 'FAILED') assert.notEqual(status.state, 'FAILED', status.error)
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
    assert.fail('Task did not reach ' + terminal)
  }
  const completed = await waitFor(accepted.taskId, 'COMPLETED')
  assert.ok(completed.report?.length > 20, 'Java must return persisted report to Vue')
  const workerLog = await readFile(workerLogPath, 'utf8')
  assert.ok(workerLog.includes('task_message_received type=analysis task_id=' + accepted.taskId))
  assert.ok(!workerLog.split('\n').some(line => line.includes('queued_task_fallback') && line.includes(accepted.taskId)))
  const report = await json(await apiRequest('/analysis/report?' + new URLSearchParams({ id: String(id), goal })))
  assert.equal(report.report, completed.report)
  assert.equal(report.trace.provider, 'MockProvider')
  const transcript = await json(await apiRequest('/analysis/transcription-status?id=' + id))
  assert.ok(transcript.segments.some(segment => segment.source === 'ASR'))
  const answer = await json(await apiRequest('/analysis/follow-up?' + new URLSearchParams({ id: String(id), question: '视频主要介绍了什么？' }), { method: 'POST' }))
  assert.ok(answer.length > 5)
  const memory = await json(await apiRequest('/analysis/agent-memory?id=' + id))
  assert.ok(memory.messageCount >= 2)
  assert.ok((await json(await apiRequest('/media/list'))).some(media => media.id === id))
  // Real Python failure, not a synthetic FAILED response: remote ASR returns 503 until retries finish.
  await nativeFetch(asrFixture + '/fixture/fail', { method: 'POST' })
  const other = await json(await apiRequest('/user/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: username + 'b', password, nickname: '隔离用户' })
  }))
  setAuthToken(other.token)
  assert.equal((await apiRequest('/analysis/task-status?taskId=' + accepted.taskId)).status, 404)
  assert.equal((await apiRequest(submitPath, { method: 'POST' })).status, 404)
  const otherVideo = await upload('failing.mp4')
  const failing = await json(await apiRequest('/analysis/ai?' + new URLSearchParams({ id: String(otherVideo.mediaId), goal }), { method: 'POST' }), 202)
  assert.equal(failing.dispatchStatus, 'SENT')
  const failed = await waitFor(failing.taskId, 'FAILED')
  assert.equal(failed.attemptCount, 3)
  assert.match(failed.error, /HTTPStatusError|503/)
  await nativeFetch(asrFixture + '/fixture/reset', { method: 'POST' })
  setAuthToken(user.token)
  // Original FastAPI create/status endpoints are still a working rollback path.
  const fallback = await json(await globalThis.fetch('/api/analysis/ai?' + new URLSearchParams({ id: String(id), goal: '概括视频 回退 ' + Date.now() }), {
    method: 'POST', headers: { Authorization: 'Bearer ' + user.token }
  }), 202)
  const fallbackStatus = await waitFor(fallback.taskId, 'COMPLETED')
  assert.ok(fallbackStatus.report.length > 20)
  assert.equal((await json(await apiRequest('/analysis/agent-memory?id=' + id))).messageCount, memory.messageCount)
  assert.ok(calls.some(path => path.startsWith('/business-api/analysis/ai?')))
  assert.ok(calls.some(path => path.startsWith('/business-api/analysis/task-status?')))
  assert.ok(calls.includes('/api/media/upload-chunk'))
  const result = { taskId: accepted.taskId, javaReportChars: completed.report.length,
    workerMqReceipt: true, watchdogUsed: false, provider: report.trace.provider,
    duplicateStatuses: [202, 409], uploadResume: true, uploadDedup: true,
    followUpChars: answer.length, memoryMessages: memory.messageCount,
    foreignTaskStatus: 404, foreignMediaStatus: 404,
    failedTaskId: failing.taskId, failedAttempts: failed.attemptCount,
    pythonFallbackTaskId: fallback.taskId, pythonFallbackCompleted: true }
  if (process.env.VTRACE_FRONTEND_RESULT_FILE) await writeFile(process.env.VTRACE_FRONTEND_RESULT_FILE, JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result))
})
