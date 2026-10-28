const TASK_ENDPOINTS = new Set(['/analysis/ai', '/analysis/task-status'])

export function apiBaseFor(path, {
  pythonBase = '/api', businessBase = '/business-api', analysisBackend = 'java'
} = {}) {
  const pathname = path.split('?')[0]
  const base = analysisBackend === 'python' || !TASK_ENDPOINTS.has(pathname)
    ? pythonBase : businessBase
  return base.replace(/\/$/, '')
}

export function queuedMessage(payload, duplicate = false) {
  if (duplicate) return '正在恢复已存在的分析任务...'
  if (payload.dispatchStatus === 'PENDING') {
    return '任务已保存，消息投递待确认，正在等待 Worker 处理。'
  }
  return '任务已排队，等待 Worker 接收...'
}
