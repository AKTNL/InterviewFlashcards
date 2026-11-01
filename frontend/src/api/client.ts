export interface ResponseMeta {
  requestId: string
  serverTime: string
}

export interface ApiResponse<T> {
  data: T
  meta: ResponseMeta
}

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly requestId?: string,
    public readonly fields: string[] = [],
    public readonly details: unknown = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  get retryable(): boolean {
    return ['NETWORK_ERROR', 'REQUEST_TIMEOUT', 'INVALID_RESPONSE', 'SERVICE_UNAVAILABLE', 'STORAGE_UNAVAILABLE', 'INTERNAL_ERROR', 'TASK_LIMITED', 'CAPACITY_EXCEEDED'].includes(this.code)
  }
}

let csrf: { headerName: string; token: string } | undefined
let csrfRequest: Promise<void> | undefined
let accountId: string | null = null
let identityBlocked = false
let generation = 0
let sessionAbort = new AbortController()
const identityKey = 'flashcards.identity-change'
function identityStamp() { try { return localStorage.getItem(identityKey) } catch { return null } }
let stamp = identityStamp()
export function resetCsrf() { csrf = undefined; csrfRequest = undefined }
export function invalidateRequests() {
  generation++; sessionAbort.abort(); sessionAbort = new AbortController(); resetCsrf()
}
export function setRequestAccount(id: string | null) {
  if(id) identityBlocked = false
  if (accountId !== id) { invalidateRequests(); accountId = id }
}
export function announceIdentityChange() {
  invalidateRequests(); stamp = crypto.randomUUID()
  try { localStorage.setItem(identityKey, stamp) } catch { /* BroadcastChannel handles open tabs. */ }
  identityChannel?.postMessage(stamp)
}
function changedIdentity() {
  identityBlocked = true
  stamp = identityStamp(); invalidateRequests(); accountId = null
  window.dispatchEvent(new Event('identity-changed'))
}
const identityChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('flashcards-identity') : undefined
identityChannel?.addEventListener('message', event => { if(event.data !== stamp) changedIdentity() })
window.addEventListener('storage', event => { if ((event.key === identityKey || event.key === null) && identityStamp() !== stamp) changedIdentity() })

export async function request<T>(path: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  if (identityStamp() !== stamp) changedIdentity()
  if(identityBlocked && !path.startsWith('/api/auth/') && path !== '/api/health') throw new ApiError('ACCOUNT_CHANGED', '请重新登录后继续', 409)
  const current = generation
  const owner = accountId
  const identitySignal = sessionAbort.signal
  const assertActive = () => {
    if (identityStamp() !== stamp) changedIdentity()
    if (current !== generation) throw new ApiError('ACCOUNT_CHANGED', '登录身份已变化，请重新确认身份后继续', 409)
  }
  // Restrict all requests to the same-origin API; credentials never go to arbitrary URLs.
  if (!path.startsWith('/api/') || path.includes('\\')) {
    throw new ApiError('INVALID_REQUEST', '请求地址不正确', 0)
  }
  const writing = !['GET', 'HEAD', 'OPTIONS'].includes((options.method ?? 'GET').toUpperCase())
  if (writing && !csrf) {
    csrfRequest ??= request<{ headerName: string; token: string }>('/api/auth/csrf')
      .then(result => { assertActive(); csrf = result.data }).finally(() => { if(current === generation) csrfRequest = undefined })
    await csrfRequest
  }
  assertActive()
  const timeout = new AbortController()
  const timer = setTimeout(() => timeout.abort(), 10_000)
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...Object.fromEntries(new Headers(options.headers)),
        ...(owner && !['/api/auth/me', '/api/auth/login', '/api/auth/csrf'].includes(path) ? { 'X-Account-ID': owner } : {}),
        ...(writing && csrf ? { [csrf.headerName]: csrf.token } : {}) },
      signal: AbortSignal.any([identitySignal, timeout.signal, ...(options.signal ? [options.signal] : [])]),
    })
    const requestId = response.headers.get('X-Request-ID') ?? undefined
    // A reverse proxy may return HTML on 502: never render its raw body as an error.
    const payload: unknown = await response.json().catch(() => null)
    assertActive()
    if (owner && !path.startsWith('/api/auth/') && response.headers.get('X-Account-ID') && response.headers.get('X-Account-ID') !== owner) {
      changedIdentity(); throw new ApiError('ACCOUNT_CHANGED', '登录账号已变化，请重新确认身份', 409)
    }
    if (!response.ok) {
      const error = isObject(payload) && isObject(payload.error) ? payload.error : null
      if (error?.code === 'CSRF_INVALID' || response.status === 401) resetCsrf()
      if (error?.code === 'UNAUTHENTICATED') window.dispatchEvent(new Event('session-expired'))
      if (error?.code === 'ACCOUNT_CHANGED') changedIdentity()
      throw new ApiError(
        typeof error?.code === 'string' ? error.code : response.status >= 500 ? 'SERVICE_UNAVAILABLE' : 'HTTP_ERROR',
        typeof error?.message === 'string' ? error.message : '暂时无法完成请求，请稍后重试',
        response.status,
        requestId,
        Array.isArray(error?.fields) ? error.fields.filter((field): field is string => typeof field === 'string') : [],
        error?.details ?? null,
      )
    }
    if (path === '/api/auth/login' || path === '/api/auth/logout') resetCsrf()
    if (!isObject(payload) || !('data' in payload) || !isObject(payload.meta)
      || typeof payload.meta.requestId !== 'string' || typeof payload.meta.serverTime !== 'string') {
      throw new ApiError('INVALID_RESPONSE', '服务响应格式不正确，请稍后重试', response.status, requestId)
    }
    return payload as unknown as ApiResponse<T>
  } catch (error) {
    if (current !== generation) throw new ApiError('ACCOUNT_CHANGED', '登录身份已变化，请重新确认身份后继续', 409)
    if (error instanceof ApiError) throw error
    if (options.signal?.aborted) throw new ApiError('REQUEST_CANCELLED', '请求已取消', 0)
    if (timeout.signal.aborted) throw new ApiError('REQUEST_TIMEOUT', '连接超时，请重试', 0)
    throw new ApiError('NETWORK_ERROR', '无法连接服务，请检查网络后重试', 0)
  } finally {
    clearTimeout(timer)
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
