import { ApiError, request } from '../api/client'
import type { Mutation, QueueStorage, Snapshot, Source, WriteResult } from './types'

export const syncApi = {
  snapshot: async () => (await request<Snapshot>('/api/sync/snapshot')).data,
  receipt: async (item: Mutation) => {
    try { return (await request<WriteResult>(`/api/sync/receipts/${item.operationId}?dataEpoch=${item.dataEpoch}`)).data }
    catch (error) { if (error instanceof ApiError && error.code === 'NOT_FOUND') return null; throw error }
  },
  source: async (id: string) => (await request<Source>(`/api/sources/${id}`)).data,
  send: async (item: Mutation): Promise<WriteResult> => {
    if (item.kind === 'preferences.save' || item.kind === 'session.daily') return (await request<WriteResult>(
      item.kind === 'preferences.save' ? '/api/preferences' : '/api/learning-sessions/daily', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...item.payload,
          operationId: item.operationId, dataEpoch: item.dataEpoch, ...(item.kind === 'preferences.save' ? { expectedVersion: item.expectedVersion } : {}) }),
      })).data
    if (item.kind === 'session.start' || item.kind === 'review.write') return (await request<WriteResult>(
      item.kind === 'session.start' ? '/api/learning-sessions' : `/api/learning-sessions/${item.payload?.sessionId}/review`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(item.kind === 'session.start'
          ? { operationId: item.operationId, dataEpoch: item.dataEpoch, cardIds: item.payload?.cardIds }
          : { operationId: item.operationId, dataEpoch: item.dataEpoch, expectedVersion: item.expectedVersion, action: item.action, payload: item.payload }),
      })).data
    if (item.kind === 'card.update') return (await request<WriteResult>(`/api/cards/${item.cardId}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operationId: item.operationId,
        dataEpoch: item.dataEpoch, expectedVersion: item.expectedVersion, action: item.action, payload: item.payload }),
    })).data
    if (item.kind === 'chapter.review') return (await request<WriteResult>(`/api/chapters/${item.chapterId}/review`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operationId: item.operationId,
        dataEpoch: item.dataEpoch, expectedVersion: item.expectedVersion, action: item.action, payload: item.payload }),
    })).data
    if (item.kind === 'import.retry') return (await request<WriteResult>(`/api/import-jobs/${item.jobId}/retry`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operationId: item.operationId,
        dataEpoch: item.dataEpoch, expectedVersion: item.expectedVersion }),
    })).data
    if (item.kind === 'source.upload') {
      const form = new FormData()
      form.append('file', item.file!, item.name)
      form.append('operationId', item.operationId); form.append('dataEpoch', item.dataEpoch)
      return (await request<WriteResult>('/api/sources', { method: 'POST', body: form })).data
    }
    return (await request<WriteResult>(`/api/sources/${item.sourceId}`, { method: 'PATCH',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operationId: item.operationId,
        dataEpoch: item.dataEpoch, expectedVersion: item.expectedVersion, name: item.name }) })).data
  },
}

export class MutationQueue {
  private running = false
  constructor(private storage: QueueStorage, private api = syncApi, private now = Date.now) {}

  async entries(userId: string) {
    return (await this.storage.all()).filter(item => item.userId === userId).sort((a, b) => a.createdAt - b.createdAt)
  }
  async enqueue(userId: string, dataEpoch: string, change: Pick<Mutation, 'kind' | 'name' | 'sourceId' | 'jobId' | 'chapterId' | 'cardId' | 'action' | 'payload' | 'expectedVersion' | 'file'>) {
    const item: Mutation = { ...change, operationId: crypto.randomUUID(), userId, dataEpoch, createdAt: this.now(),
      status: 'pending', attempts: 0, nextAttemptAt: 0 }
    // Vue forms contain nested reactive proxies, which IndexedDB cannot structured-clone.
    // The chapter payload is JSON; detach it here while keeping upload Blobs intact.
    if (change.payload) item.payload = JSON.parse(JSON.stringify(change.payload)) as Record<string, unknown>
    await this.storage.put(item)
    return item
  }
  async remove(id: string) { await this.storage.remove(id) }

  async run(userId: string, active: () => boolean, changed: () => Promise<void>, snapshot: (value: Snapshot) => void, force = false) {
    if (this.running) return
    this.running = true
    try {
      const fresh = await this.api.snapshot()
      if (!active()) return
      snapshot(fresh)
      for (const item of await this.entries(userId)) {
        if (!active()) return
        if (item.status === 'synced') continue
        // A persisted syncing entry is an uncertain write after reload; keep its original ID.
        if (item.dataEpoch !== fresh.dataEpoch) {
          item.status = 'needs-attention'; item.error = { code: 'EPOCH_EXPIRED', message: '数据已替换，本地内容保留供核对，请勿直接重试。' }
          await this.storage.put(item); await changed(); continue
        }
        if (item.status === 'failed' || item.status === 'needs-attention') continue
        if (!force && item.nextAttemptAt > this.now()) continue
        item.status = 'syncing'; await this.storage.put(item); await changed()
        try {
          // Resolve a lost response before checking versions: its write may already have advanced the version.
          let result = await this.api.receipt(item)
          if (!active()) return
          if (!result) {
            if (item.kind === 'source.rename') {
              const current = await this.api.source(item.sourceId!)
              if (current.version !== item.expectedVersion) throw new ApiError('VERSION_CONFLICT', '资料已有新版本，请核对本地与服务端内容。', 409, undefined, [], { current })
            }
            if (!active()) return
            result = await this.api.send(item)
          }
          if (result.mutation?.operationId !== item.operationId || result.mutation.dataEpoch !== item.dataEpoch
              || result.mutation.entityVersion !== (item.kind === 'preferences.save' ? result.preferences?.version : item.kind === 'session.daily' || item.kind === 'session.start' || item.kind === 'review.write' ? result.session?.version : item.kind === 'card.update' ? result.card?.version : item.kind === 'chapter.review' ? result.chapter?.version : item.kind === 'import.retry' ? result.job?.version : result.source?.version)
              || (item.kind === 'review.write' && result.session?.id !== item.payload?.sessionId)
              || (item.kind === 'card.update' && result.card?.id !== item.cardId)
              || (item.kind === 'chapter.review' && result.chapter?.id !== item.chapterId)
              || (item.kind === 'import.retry' && result.job?.id !== item.jobId) || !Number.isSafeInteger(result.mutation.revision))
            throw new ApiError('INVALID_RESPONSE', '未收到有效确认，将保留操作供重试。', 0)
          item.status = 'synced'; item.result = result; item.file = undefined; item.error = undefined
        } catch (cause) {
          const error = cause instanceof ApiError ? cause : new ApiError('NETWORK_ERROR', '连接失败，内容已保留。', 0)
          item.attempts++
          item.error = { code: error.code, message: error.message, details: error.details, requestId: error.requestId }
          item.status = error.retryable || error.code === 'UNAUTHENTICATED' ? 'pending'
            : ['VERSION_CONFLICT', 'EPOCH_EXPIRED', 'OPERATION_ID_REUSED', 'NOT_FOUND', 'CSRF_INVALID'].includes(error.code) ? 'needs-attention' : 'failed'
          item.nextAttemptAt = this.now() + Math.min(60_000, 1000 * 2 ** Math.min(item.attempts, 6))
          await this.storage.put(item); await changed()
          if (item.status === 'pending' || error.code === 'EPOCH_EXPIRED') break
          continue
        }
        await this.storage.put(item); await changed()
      }
      if (active()) snapshot(await this.api.snapshot())
    } finally { this.running = false }
  }
}
