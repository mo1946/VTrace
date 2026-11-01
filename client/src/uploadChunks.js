import { apiRequest } from './api.js'

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

// Only chunk writes are retried: initialization/completion are not blindly reposted.
export async function uploadChunkWithRetry({
  uploadId, index, totalChunks, chunk,
  request = apiRequest, wait = sleep, maxAttempts = 3, timeoutMs = 120000
}) {
  const attempts = Math.max(1, maxAttempts)
  for (let attempt = 0; attempt < attempts; attempt++) {
    const formData = new FormData()
    formData.append('uploadId', uploadId)
    formData.append('chunkIndex', String(index))
    formData.append('totalChunks', String(totalChunks))
    formData.append('file', chunk)

    let response
    try {
      response = await request('/media/upload-chunk', {
        method: 'POST', body: formData, timeoutMs
      })
    } catch (error) {
      if (attempt === attempts - 1) throw error
      await wait(500 * 2 ** attempt)
      continue
    }
    if (response.ok) return

    const message = await response.text()
    const retryable = response.status === 408 || response.status === 429 || response.status >= 500
    if (!retryable || attempt === attempts - 1) {
      throw new Error(message || `分片 ${index} 上传失败`)
    }
    const retryAfter = response.headers.get('Retry-After')
    const seconds = retryAfter === null ? NaN : Number(retryAfter)
    const retryAt = retryAfter === null ? NaN : Date.parse(retryAfter)
    const serverDelay = Number.isFinite(seconds) ? seconds * 1000
      : Number.isFinite(retryAt) ? retryAt - Date.now() : 0
    await wait(Math.max(500 * 2 ** attempt, serverDelay))
  }
}
