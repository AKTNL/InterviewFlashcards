import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '../api/client'
import { queueStorage } from '../sync/database'
import { syncApi } from '../sync/queue'
import type { Mutation, Snapshot, Source } from '../sync/types'
import { useSyncStore } from './sync'

vi.mock('../sync/database', () => ({ queueStorage: { all: vi.fn(), put: vi.fn(), remove: vi.fn() } }))
const user = { id: 'user', username: 'user', dataEpoch: 'epoch', revision: 0, learningTimezone: 'Asia/Shanghai' }
const source: Source = { id: 'source', version: 0, originalFilename: 'old.pdf', pageCount: 1, byteSize: 10, createdAt: '2026-10-31T00:00:00Z' }
const snapshot: Snapshot = { dataEpoch: 'epoch', revision: 0, sources: [source] }
let rows: Map<string, Mutation>
beforeEach(() => {
  setActivePinia(createPinia()); rows = new Map()
  vi.mocked(queueStorage.all).mockImplementation(async () => structuredClone([...rows.values()]))
  vi.mocked(queueStorage.put).mockImplementation(async item => { rows.set(item.operationId, structuredClone(item)) })
  vi.mocked(queueStorage.remove).mockImplementation(async id => { rows.delete(id) })
  vi.spyOn(syncApi, 'snapshot').mockResolvedValue(snapshot)
  vi.spyOn(syncApi, 'receipt').mockResolvedValue(null)
  vi.spyOn(syncApi, 'source').mockResolvedValue(source)
})
function pending(): Mutation {
  return { userId: user.id, dataEpoch: 'epoch', operationId: 'operation', createdAt: 1, kind: 'source.rename',
    sourceId: source.id, name: 'local.pdf', expectedVersion: 0, status: 'pending', attempts: 0, nextAttemptAt: 0 }
}
it('shows initialization storage errors and recovers through the visible reconnect action', async () => {
  vi.mocked(queueStorage.all).mockRejectedValueOnce(new Error('storage disabled'))
  const store = useSyncStore()
  await store.connect(user)
  expect(store.error).toContain('本地待同步内容')
  expect(syncApi.snapshot).not.toHaveBeenCalled()
  await store.refresh(true)
  expect(store.error).toBe(''); expect(store.state).toEqual(snapshot)
  store.stop()
})
it('requires explicit CSRF recovery and retries with the original operation ID', async () => {
  const item = pending(); rows.set(item.operationId, item)
  const send = vi.spyOn(syncApi, 'send').mockRejectedValueOnce(new ApiError('CSRF_INVALID', '安全凭证过期', 403))
    .mockResolvedValueOnce({ source: { ...source, version: 1 }, mutation: { operationId: item.operationId,
      dataEpoch: item.dataEpoch, entityVersion: 1, revision: 1, serverTime: '2026-10-31T00:00:00Z' } })
  const store = useSyncStore(); await store.connect(user)
  expect(store.items[0]?.status).toBe('needs-attention')
  await store.refresh(true); expect(send).toHaveBeenCalledTimes(1)
  await store.retrySecurity(store.items[0]!)
  expect(send).toHaveBeenCalledTimes(2)
  expect(send.mock.calls[1]?.[0].operationId).toBe(item.operationId)
  expect(store.items[0]?.status).toBe('synced')
  store.stop()
})
it('keeps the original conflict if saving the replacement operation fails', async () => {
  const item = { ...pending(), status: 'needs-attention' as const, error: { code: 'VERSION_CONFLICT', message: '冲突' } }
  rows.set(item.operationId, item)
  const store = useSyncStore(); await store.connect(user)
  vi.mocked(queueStorage.put).mockRejectedValueOnce(new Error('quota'))
  await expect(store.resubmit(store.items[0]!)).rejects.toThrow('quota')
  expect(rows.get(item.operationId)?.name).toBe('local.pdf')
  expect(queueStorage.remove).not.toHaveBeenCalled()
  store.stop()
})
it('does not move an old user’s conflict payload into a newly signed-in user’s queue', async () => {
  const item = { ...pending(), status: 'needs-attention' as const, error: { code: 'VERSION_CONFLICT', message: '冲突' } }
  rows.set(item.operationId, item)
  const store = useSyncStore(); await store.connect(user)
  let finish!: (value: Source) => void
  vi.mocked(syncApi.source).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const resubmission = store.resubmit(store.items[0]!)
  store.stop()
  vi.mocked(syncApi.snapshot).mockResolvedValue({ ...snapshot, dataEpoch: 'other-epoch', sources: [] })
  await store.connect({ ...user, id: 'other-user', dataEpoch: 'other-epoch' })
  finish(source); await resubmission
  expect(rows.size).toBe(1); expect(rows.get(item.operationId)?.userId).toBe(user.id)
  expect(store.items).toEqual([])
  store.stop()
})
