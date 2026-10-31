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
  ) {
    super(message)
    this.name = 'ApiError'
  }

  get retryable(): boolean {
    return ['NETWORK_ERROR', 'REQUEST_TIMEOUT', 'SERVICE_UNAVAILABLE'].includes(this.code)
  }
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  // Restrict all requests to the same-origin API; credentials never go to arbitrary URLs.
  if (!path.startsWith('/api/') || path.includes('\\')) {
    throw new ApiError('INVALID_REQUEST', '请求地址不正确', 0)
  }
  const timeout = new AbortController()
  const timer = setTimeout(() => timeout.abort(), 10_000)
  try {
    const response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...Object.fromEntries(new Headers(options.headers)) },
      signal: options.signal ? AbortSignal.any([options.signal, timeout.signal]) : timeout.signal,
    })
    const requestId = response.headers.get('X-Request-ID') ?? undefined
    // A reverse proxy may return HTML on 502: never render its raw body as an error.
    const payload: unknown = await response.json().catch(() => null)
    if (!response.ok) {
      const error = isObject(payload) && isObject(payload.error) ? payload.error : null
      throw new ApiError(
        typeof error?.code === 'string' ? error.code : response.status >= 500 ? 'SERVICE_UNAVAILABLE' : 'HTTP_ERROR',
        typeof error?.message === 'string' ? error.message : '暂时无法完成请求，请稍后重试',
        response.status,
        requestId,
        Array.isArray(error?.fields) ? error.fields.filter((field): field is string => typeof field === 'string') : [],
      )
    }
    if (!isObject(payload) || !('data' in payload) || !isObject(payload.meta)
      || typeof payload.meta.requestId !== 'string' || typeof payload.meta.serverTime !== 'string') {
      throw new ApiError('INVALID_RESPONSE', '服务响应格式不正确，请稍后重试', response.status, requestId)
    }
    return payload as unknown as ApiResponse<T>
  } catch (error) {
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
