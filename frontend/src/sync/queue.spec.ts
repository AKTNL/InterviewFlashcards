import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { ApiError } from '../api/client'
import { MutationQueue, syncApi } from './queue'
import type { Mutation, QueueStorage, Snapshot, Source, WriteResult } from './types'

const source: Source = { id: 'source', originalFilename: '远端.pdf', version: 0, pageCount: 1, byteSize: 100, createdAt: '2026-10-31T00:00:00Z' }
const snapshot: Snapshot = { dataEpoch: 'epoch', revision: 0, sources: [source] }
let rows: Map<string, Mutation>
let storage: QueueStorage
let api: typeof syncApi
let now: number
let queue: MutationQueue
const active = () => true
const changed = async () => {}
const show = () => {}
beforeEach(() => {
  rows = new Map(); now = 100
  storage = { all: async () => structuredClone([...rows.values()]),
    put: async item => { rows.set(item.operationId, structuredClone(item)) }, remove: async id => { rows.delete(id) } }
  api = { snapshot: vi.fn().mockResolvedValue(snapshot), receipt: vi.fn().mockResolvedValue(null),
    source: vi.fn().mockResolvedValue(source), send: vi.fn() }
  queue = new MutationQueue(storage, api, () => now)
})
async function enqueue() {
  return queue.enqueue('user', 'epoch', { kind: 'source.rename', name: '本地.pdf', sourceId: source.id, expectedVersion: 0 })
}
function receipt(item: Mutation): WriteResult {
  return { source: { ...source, originalFilename: item.name, version: 1 },
    mutation: { operationId: item.operationId, dataEpoch: item.dataEpoch, entityVersion: 1, revision: 1, serverTime: '2026-10-31T00:00:00Z' } }
}
it('recovers an import retry receipt after reload without sending a second retry', async () => {
  const item = await queue.enqueue('user', 'epoch', { kind: 'import.retry', name: '解析', jobId: 'job', expectedVersion: 3 })
  await storage.put({ ...item, status: 'syncing' })
  const result = { job: { id: 'job', version: 4 }, mutation: { operationId: item.operationId, dataEpoch: 'epoch', entityVersion: 4, revision: 8, serverTime: '2026-10-31T00:00:00Z' } }
  vi.mocked(api.receipt).mockResolvedValue(result)
  await new MutationQueue(storage, api).run('user', active, changed, show)
  expect(api.send).not.toHaveBeenCalled()
  expect(api.source).not.toHaveBeenCalled()
  expect(rows.get(item.operationId)?.status).toBe('synced')
})
it('keeps import version conflicts for attention instead of replaying the task', async () => {
  const item = await queue.enqueue('user', 'epoch', { kind: 'import.retry', name: '解析', jobId: 'job', expectedVersion: 3 })
  vi.mocked(api.send).mockRejectedValue(new ApiError('VERSION_CONFLICT', '任务已更新', 409))
  await queue.run('user', active, changed, show)
  await queue.run('user', active, changed, show, true)
  expect(api.send).toHaveBeenCalledTimes(1)
  expect(rows.get(item.operationId)?.status).toBe('needs-attention')
})
it('rejects an import receipt belonging to a different job', async () => {
  const item = await queue.enqueue('user', 'epoch', { kind: 'import.retry', name: '解析', jobId: 'job', expectedVersion: 3 })
  vi.mocked(api.send).mockResolvedValue({ job: { id: 'other-job', version: 4 }, mutation: { operationId: item.operationId, dataEpoch: 'epoch', entityVersion: 4, revision: 8, serverTime: '2026-10-31T00:00:00Z' } })
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe('pending')
  expect(rows.get(item.operationId)?.error?.code).toBe('INVALID_RESPONSE')
})
it('persists before sending, keeps the ID through timeout/backoff and accepts only server confirmation', async () => {
  const item = await enqueue()
  expect(rows.get(item.operationId)?.status).toBe('pending')
  vi.mocked(api.send).mockImplementationOnce(async current => {
    expect(rows.get(current.operationId)?.status).toBe('syncing')
    throw new ApiError('REQUEST_TIMEOUT', '超时', 0)
  }).mockResolvedValueOnce(receipt(item))
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe('pending')
  await queue.run('user', active, changed, show)
  expect(api.send).toHaveBeenCalledTimes(1)
  now = 5000
  await new MutationQueue(storage, api, () => now).run('user', active, changed, show)
  expect(api.send).toHaveBeenCalledTimes(2)
  expect(vi.mocked(api.send).mock.calls[1]![0].operationId).toBe(item.operationId)
  expect(rows.get(item.operationId)?.status).toBe('synced')
})
it('recovers a lost response after reload via its receipt before checking an advanced entity version', async () => {
  const item = await enqueue()
  await storage.put({ ...item, status: 'syncing' })
  vi.mocked(api.receipt).mockResolvedValue(receipt(item))
  vi.mocked(api.source).mockResolvedValue({ ...source, version: 9 })
  await new MutationQueue(storage, api).run('user', active, changed, show)
  expect(api.source).not.toHaveBeenCalled(); expect(api.send).not.toHaveBeenCalled()
  expect(rows.get(item.operationId)?.status).toBe('synced')
})
it('retains both conflict values and never automatically retries a conflict, even on manual reconnect', async () => {
  const item = await enqueue()
  vi.mocked(api.source).mockResolvedValue({ ...source, version: 2 })
  await queue.run('user', active, changed, show)
  const retained = rows.get(item.operationId)!
  expect(retained.status).toBe('needs-attention'); expect(retained.name).toBe('本地.pdf')
  expect(retained.error?.details).toEqual({ current: { ...source, version: 2 } })
  await queue.run('user', active, changed, show, true)
  expect(api.send).not.toHaveBeenCalled(); expect(api.source).toHaveBeenCalledTimes(1)
})
it('invalidates all old epoch operations while retaining local payloads and isolating another user', async () => {
  const item = await enqueue()
  const other = await queue.enqueue('other-user', 'epoch', { kind: 'source.upload', name: 'other.pdf', file: new Blob(['pdf']) })
  vi.mocked(api.snapshot).mockResolvedValue({ ...snapshot, dataEpoch: 'restored' })
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe('needs-attention')
  expect(rows.get(item.operationId)?.name).toBe('本地.pdf')
  expect(rows.get(other.operationId)?.status).toBe('pending')
  expect(api.receipt).not.toHaveBeenCalled(); expect(api.send).not.toHaveBeenCalled()
})
it.each(['VALIDATION_ERROR', 'UNAUTHENTICATED', 'OPERATION_ID_REUSED'])('classifies %s without treating it as a successful write', async code => {
  const item = await enqueue()
  vi.mocked(api.send).mockRejectedValue(new ApiError(code, '失败', 400))
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe(code === 'VALIDATION_ERROR' ? 'failed' : code === 'UNAUTHENTICATED' ? 'pending' : 'needs-attention')
  expect(rows.get(item.operationId)?.result).toBeUndefined()
})
it('does not send when preflight is offline, and never accepts an unrelated or malformed confirmation', async () => {
  const item = await enqueue()
  vi.mocked(api.snapshot).mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '离线', 0))
  await expect(queue.run('user', active, changed, show)).rejects.toThrow('离线')
  expect(api.send).not.toHaveBeenCalled()
  vi.mocked(api.send).mockResolvedValue({ ...receipt(item), mutation: { ...receipt(item).mutation, operationId: 'wrong' } })
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe('pending')
  expect(rows.get(item.operationId)?.error?.code).toBe('INVALID_RESPONSE')
})
it('fails enqueue on storage/quota failure without sending or displaying saved content', async () => {
  vi.spyOn(storage, 'put').mockRejectedValue(new Error('quota'))
  await expect(enqueue()).rejects.toThrow('quota')
  expect(rows.size).toBe(0); expect(api.send).not.toHaveBeenCalled()
})
it('stops before sending if the signed-in user changed during preflight', async () => {
  await enqueue()
  await queue.run('user', () => false, changed, show)
  expect(api.receipt).not.toHaveBeenCalled(); expect(api.send).not.toHaveBeenCalled()
})
it('retains complete chapter edits through response loss and accepts only the matching chapter receipt', async () => {
  const payload = { draftId: 'draft', reason: '核对原文', value: { question: '本地修正', sources: [{ page: 10, bbox: [0, 0, 600, 800] }] } }
  const item = await queue.enqueue('user', 'epoch', { kind: 'chapter.review', name: 'Java 编辑', chapterId: 'chapter', action: 'EDIT', expectedVersion: 3, payload })
  const result = { chapter: { id: 'chapter', version: 4 }, mutation: { operationId: item.operationId, dataEpoch: 'epoch', entityVersion: 4, revision: 8, serverTime: '2026-10-31T00:00:00Z' } }
  vi.mocked(api.send).mockRejectedValueOnce(new ApiError('REQUEST_TIMEOUT', '超时', 0))
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.payload).toEqual(payload); expect(rows.get(item.operationId)?.status).toBe('pending')
  vi.mocked(api.receipt).mockResolvedValue(result)
  await new MutationQueue(storage, api, () => 5000).run('user', active, changed, show)
  expect(api.send).toHaveBeenCalledTimes(1); expect(rows.get(item.operationId)?.status).toBe('synced')
})
it('preserves chapter conflict payloads and current server content without automatic replay', async () => {
  const payload = { draftId: 'draft', value: { question: '本地' } }
  const item = await queue.enqueue('user', 'epoch', { kind: 'chapter.review', name: '章节', chapterId: 'chapter', action: 'EDIT', expectedVersion: 3, payload })
  vi.mocked(api.send).mockRejectedValue(new ApiError('VERSION_CONFLICT', '版本冲突', 409, undefined, [], { current: { version: 4, items: [{ question: '服务端' }] } }))
  await queue.run('user', active, changed, show)
  expect(rows.get(item.operationId)?.status).toBe('needs-attention'); expect(rows.get(item.operationId)?.payload).toEqual(payload)
  expect(rows.get(item.operationId)?.error?.details).toEqual({ current: { version: 4, items: [{ question: '服务端' }] } })
  await queue.run('user', active, changed, show, true); expect(api.send).toHaveBeenCalledTimes(1)
})
it('detaches nested Vue form proxies before durable storage and preserves the submitted content', async () => {
  const form = reactive({ question: '已提交的修正', sources: [{ page: 10, bbox: [0, 65.348709, 612, 792] }] })
  const item = await queue.enqueue('user', 'epoch', { kind: 'chapter.review', name: '编辑', chapterId: 'chapter', action: 'EDIT', payload: { value: form } })
  form.question = '提交后继续输入'; form.sources[0]!.bbox[0] = 20
  expect(rows.get(item.operationId)?.payload).toEqual({ value: { question: '已提交的修正', sources: [{ page: 10, bbox: [0, 65.348709, 612, 792] }] } })
})
