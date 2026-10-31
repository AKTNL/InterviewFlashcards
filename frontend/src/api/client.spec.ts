import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('same-origin API client', () => {
  it('keeps credentials same-origin and accepts the envelope', async () => {
    const payload = { data: { status: 'UP' }, meta: { requestId: 'test-id', serverTime: '2026-10-31T00:00:00Z' } }
    const fetcher = vi.fn().mockResolvedValue(Response.json(payload))
    vi.stubGlobal('fetch', fetcher)
    expect(await request('/api/health')).toEqual(payload)
    expect(fetcher).toHaveBeenCalledWith('/api/health', expect.objectContaining({ credentials: 'same-origin' }))
    await expect(request('https://other.example/api/health')).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it.each([
    [401, 'UNAUTHENTICATED'], [403, 'FORBIDDEN'], [400, 'VALIDATION_ERROR'], [409, 'VERSION_CONFLICT'],
  ])('preserves %i errors without automatically retrying', async (status, code) => {
    const fetcher = vi.fn().mockResolvedValue(Response.json({ error: { code, message: '需要处理', fields: ['name'] } },
      { status, headers: { 'X-Request-ID': 'trace-1' } }))
    vi.stubGlobal('fetch', fetcher)
    const error = await request('/api/example').catch((cause: unknown) => cause)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code, status, requestId: 'trace-1', retryable: false, fields: ['name'] })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('does not expose reverse-proxy HTML and marks 502 retryable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>private upstream information</html>', { status: 502 })))
    await expect(request('/api/health')).rejects.toMatchObject({ code: 'SERVICE_UNAVAILABLE', retryable: true })
  })

  it('rejects malformed success envelopes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ status: 'UP' })))
    await expect(request('/api/health')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('distinguishes a disconnected network', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('internal connection info')))
    await expect(request('/api/health')).rejects.toMatchObject({ code: 'NETWORK_ERROR', retryable: true })
  })

  it('times out stalled requests and releases the timer', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_path: string, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const result = expect(request('/api/health')).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT', retryable: true })
    await vi.advanceTimersByTimeAsync(10_000)
    await result
    expect(vi.getTimerCount()).toBe(0)
  })
})
