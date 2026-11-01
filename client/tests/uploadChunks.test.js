import test from 'node:test'
import assert from 'node:assert/strict'
import { uploadChunkWithRetry } from '../src/uploadChunks.js'

const chunk = new Blob(['video-part'])
const base = { uploadId: 'upload-1', index: 2, totalChunks: 4, chunk }

test('lost response retries the same chunk without creating a new upload', async () => {
  let calls = 0
  const delays = []
  await uploadChunkWithRetry({ ...base, wait: async delay => delays.push(delay),
    request: async (path, options) => {
      calls++
      assert.equal(path, '/media/upload-chunk')
      assert.equal(options.timeoutMs, 120000)
      assert.equal(options.body.get('uploadId'), 'upload-1')
      assert.equal(options.body.get('chunkIndex'), '2')
      assert.equal(await options.body.get('file').text(), 'video-part')
      if (calls === 1) throw new TypeError('response lost after successful disk write')
      return new Response('ok')
    }
  })
  assert.equal(calls, 2)
  assert.deepEqual(delays, [500])
})

test('temporary server failures back off and respect Retry-After', async () => {
  const responses = [new Response('busy', { status: 503 }),
    new Response('limited', { status: 429, headers: { 'Retry-After': '2' } }), new Response('ok')]
  const delays = []
  await uploadChunkWithRetry({ ...base, request: async () => responses.shift(),
    wait: async delay => delays.push(delay) })
  assert.deepEqual(delays, [500, 2000])
})

test('authentication, missing session, and invalid chunk errors are not retried', async () => {
  for (const status of [400, 401, 403, 404, 413]) {
    let calls = 0
    await assert.rejects(uploadChunkWithRetry({ ...base,
      request: async () => { calls++; return new Response('permanent error', { status }) },
      wait: async () => assert.fail('must not wait') }), /permanent error/)
    assert.equal(calls, 1)
  }
})

test('persistent network failures stop after three attempts and two waits', async () => {
  let calls = 0
  const delays = []
  await assert.rejects(uploadChunkWithRetry({ ...base,
    request: async () => { calls++; throw new Error('request timed out') },
    wait: async delay => delays.push(delay) }), /request timed out/)
  assert.equal(calls, 3)
  assert.deepEqual(delays, [500, 1000])
})

test('persistent server failures stop without retrying completion endpoints', async () => {
  let calls = 0
  await assert.rejects(uploadChunkWithRetry({ ...base,
    request: async path => {
      calls++
      assert.equal(path, '/media/upload-chunk')
      return new Response('server unavailable', { status: 502 })
    }, wait: async () => {} }), /server unavailable/)
  assert.equal(calls, 3)
})
