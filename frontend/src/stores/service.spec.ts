import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { getHealth } from '../api/health'
import { useServiceStore } from './service'

vi.mock('../api/health', () => ({ getHealth: vi.fn() }))
const healthy = {
  data: { status: 'UP' as const, database: 'UP' as const, learningDate: '2026-10-31', learningTimezone: 'Asia/Shanghai' },
  meta: { requestId: 'test-id', serverTime: '2026-10-31T00:00:00Z' },
}

beforeEach(() => { setActivePinia(createPinia()) })

it('clears stale healthy status on failure and supports explicit retry', async () => {
  vi.mocked(getHealth).mockResolvedValueOnce(healthy)
    .mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '无法连接', 0))
    .mockResolvedValueOnce(healthy)
  const store = useServiceStore()
  await store.check()
  expect(store.health?.database).toBe('UP')
  await store.check()
  expect(store.health).toBeNull()
  expect(store.meta).toBeNull()
  expect(store.error?.code).toBe('NETWORK_ERROR')
  expect(store.loading).toBe(false)
  await store.check()
  expect(store.error).toBeNull()
  expect(store.health?.status).toBe('UP')
})

it('prevents duplicate in-flight checks', async () => {
  let finish!: (value: typeof healthy) => void
  vi.mocked(getHealth).mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
  const store = useServiceStore()
  const first = store.check()
  await store.check()
  expect(getHealth).toHaveBeenCalledTimes(1)
  finish(healthy)
  await first
  expect(store.loading).toBe(false)
})
