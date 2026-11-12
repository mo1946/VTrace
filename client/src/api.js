import { apiBaseFor } from './apiRouting.js'

const env = import.meta.env || {}
const apiConfig = {
  pythonBase: env.VITE_API_BASE_URL || '/api',
  businessBase: env.VITE_BUSINESS_API_BASE_URL || '/business-api',
  analysisBackend: env.VITE_ANALYSIS_BACKEND || 'java'
}
const TOKEN_KEY = 'authToken'

export function hasAuthToken() {
  return Boolean(localStorage.getItem(TOKEN_KEY))
}

export function setAuthToken(token) {
  if (!token) throw new Error('登录接口未返回有效令牌')
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
}

// Auth endpoints may receive an empty/text proxy error when the API is offline.
export async function apiJson(path, options = {}) {
  let response
  try {
    response = await apiRequest(path, options)
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error('无法连接服务，请检查网络并确认后端已启动')
    }
    throw error
  }
  const text = await response.text()
  let data
  try { data = JSON.parse(text) } catch (_) {}
  if (!response.ok) {
    const detail = data?.message || data?.detail
    if (typeof detail === 'string') throw new Error(detail)
    if (Array.isArray(detail)) {
      throw new Error(detail.map(item => item.msg).filter(Boolean).join('；') || '提交的数据格式不正确')
    }
    if (response.status >= 500) {
      throw new Error('后端服务暂不可用，请确认 Python 后端已启动（本地默认端口 9090）')
    }
    throw new Error(`请求失败（${response.status}），请稍后重试`)
  }
  if (data === undefined) throw new Error('服务器响应格式异常，请检查接口代理配置')
  return data
}

export async function apiRequest(path, options = {}) {
  const { timeoutMs = 0, ...fetchOptions } = options
  const headers = new Headers(fetchOptions.headers || {})
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const controller = timeoutMs > 0 && !fetchOptions.signal ? new AbortController() : null
  const timeout = controller ? setTimeout(() => controller.abort(), timeoutMs) : null
  try {
    const response = await fetch(`${apiBaseFor(path, apiConfig)}${path}`, {
      ...fetchOptions,
      headers,
      signal: controller?.signal || fetchOptions.signal
    })
    if (response.status === 401 && !path.startsWith('/user/')) {
      clearAuthToken()
      window.dispatchEvent(new Event('auth-expired'))
    }
    return response
  } catch (error) {
    if (controller?.signal.aborted) throw new Error(`请求超过 ${Math.ceil(timeoutMs / 1000)} 秒未响应`)
    throw error
  } finally {
    if (timeout) clearTimeout(timeout)
  }
}
