import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import type { Profile } from '../api/auth'
import { ApiError, request } from '../api/client'
import { queueStorage } from '../sync/database'
import { MutationQueue, syncApi } from '../sync/queue'
import type { Mutation, Snapshot, ReviewConflict } from '../sync/types'
import { applyChanges } from '../sync/incremental'

export const useSyncStore = defineStore('sync', () => {
  const items = shallowRef<Mutation[]>([])
  const state = shallowRef<Snapshot | null>(null)
  const error = ref('')
  const busy = ref(false)
  const notificationState = ref<'waiting' | 'connected' | 'fallback' | 'paused'>('paused')
  const conflicts = computed<ReviewConflict[]>(() => (state.value?.data?.sync_conflict ?? []).filter(r => !r.resolved && r.entity_type === 'REVIEW').map(r => ({
    id: String(r.id), cardId: String(r.entity_id), dataEpoch: String(r.data_epoch), operationId: String(r.operation_id), version: Number(r.version), resolved: false,
    local: r.client_content as ReviewConflict['local'], server: r.server_content as ReviewConflict['server'],
  })))
  const status = computed(() => items.value.some(i => i.status === 'needs-attention') || conflicts.value.length ? 'needs-attention'
    : items.value.some(i => i.status === 'failed') ? 'failed' : busy.value ? 'syncing'
      : items.value.some(i => i.status !== 'synced') ? 'pending' : error.value || !state.value ? 'failed' : 'synced')
  const queue = new MutationQueue(queueStorage)
  let user: Profile | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let failures = 0
  let notificationTimer: ReturnType<typeof setTimeout> | undefined
  let notificationAbort: AbortController | undefined
  let notificationFailures = 0
  let refreshRequested = false
  function pauseNotifications() {
    clearTimeout(notificationTimer); notificationTimer = undefined
    notificationAbort?.abort(); notificationAbort = undefined
    notificationState.value = 'paused'
  }
  function watchChanges(delay = 0) {
    if (!user || !state.value || notificationAbort || notificationTimer || document.visibilityState !== 'visible' || !navigator.onLine) return
    const current = generation
    notificationTimer = setTimeout(async () => {
      notificationTimer = undefined
      if (current !== generation || !user || !state.value) return
      const controller = new AbortController(); notificationAbort = controller
      notificationState.value = 'waiting'
      let retry: number | undefined = 100
      try {
        const cursor = await syncApi.notifications(state.value, controller.signal)
        if (current !== generation || controller.signal.aborted) return
        notificationFailures = 0; notificationState.value = 'connected'
        if (cursor.dataEpoch !== state.value?.dataEpoch || cursor.revision !== state.value?.revision || cursor.learningDate !== state.value?.learningDate) {
          if (busy.value) refreshRequested = true
          else await refresh()
        }
      } catch (cause) {
        if (current !== generation || controller.signal.aborted) return
        notificationState.value = 'fallback'; notificationFailures++
        retry = Math.min(120_000, 5000 * 2 ** Math.min(notificationFailures - 1, 5))
        if (cause instanceof ApiError && ['UNAUTHENTICATED', 'ACCOUNT_CHANGED', 'PASSWORD_CHANGE_REQUIRED'].includes(cause.code)) { retry = undefined; return }
      } finally {
        if (notificationAbort === controller) notificationAbort = undefined
        if (current === generation && !controller.signal.aborted && retry !== undefined) watchChanges(retry)
      }
    }, delay)
  }
  function visibilityChanged() {
    if (document.visibilityState !== 'visible' || !navigator.onLine) pauseNotifications()
    else watchChanges()
  }
  async function loadState(full: boolean) {
    const base = state.value
    if (!base || full) return syncApi.snapshot()
    const delta = await syncApi.changes(base)
    if (delta.fullRequired) return syncApi.snapshot()
    try { return applyChanges(base, delta) }
    catch { return syncApi.snapshot() }
  }
  async function reload() {
    const owner = user?.id; const current = generation
    const entries = owner ? await queue.entries(owner) : []
    if (current === generation) items.value = entries
  }
  function stop() {
    user = null; generation++; clearTimeout(timer); pauseNotifications(); items.value = []; state.value = null; error.value = ''; failures = 0
    notificationFailures = 0; refreshRequested = false
  }
  async function connect(profile: Profile) {
    if (user?.id !== profile.id) { stop(); user = profile }
    try { await reload() }
    catch { if(user?.id === profile.id) error.value = '无法读取本地待同步内容，请检查浏览器存储权限后重试。'; return }
    if(user?.id !== profile.id) return
    await refresh()
  }
  async function refresh(force = false) {
    if (!user || busy.value) return
    clearTimeout(timer)
    const current = generation; const owner = user.id
    const active = () => current === generation && user?.id === owner
    busy.value = true; error.value = ''
    try {
      await reload()
      if(!active()) return
      const pending = items.value.some(i => i.status === 'pending' || i.status === 'syncing')
      const revision = state.value && !force && !pending ? await syncApi.revision() : null
      if (!active()) return
      if (!revision || revision.dataEpoch !== state.value?.dataEpoch || revision.revision !== state.value?.revision || revision.learningDate !== state.value?.learningDate) {
        let first = true
        await queue.run(owner, active, reload, value => { if (active()) state.value = value }, force,
          () => { const full = force && first; first = false; return loadState(full) })
      }
      failures = 0
      if(!active()) return
      // A conflict resolved on another device must release this browser's blocked scoring operation.
      const resolved = new Set((state.value?.data?.sync_conflict ?? []).filter(r => r.resolved).map(r => r.id))
      for (const item of items.value) if (item.error?.code === 'REVIEW_CONFLICT' && item.result?.conflict && resolved.has(item.result.conflict.id)) {
        await queueStorage.put({ ...item, status: 'synced', error: undefined,
          result: { ...item.result, session: undefined, conflict: { ...item.result.conflict, resolved: true } } })
      }
      await reload()
    }
    catch (cause) { if (active()) { failures++; error.value = cause instanceof ApiError ? cause.message : '本地保存失败，请检查浏览器存储空间；内容尚未确认保存。' } }
    finally {
      busy.value = false
      if (active()) {
        watchChanges()
        if (refreshRequested) { refreshRequested = false; void refresh(); return }
        const pending = items.value.filter(item => item.status === 'pending' || item.status === 'syncing')
        const delay = failures ? Math.min(120_000, 5000 * 2 ** Math.min(failures - 1, 5))
          : pending.length ? Math.max(1000, Math.min(...pending.map(item => item.nextAttemptAt - Date.now()))) : 30_000
        if (!pending.some(item => item.error?.code === 'UNAUTHENTICATED')) timer = setTimeout(() => {
          if (document.visibilityState === 'visible') void refresh()
          else timer = setTimeout(() => { void refresh() }, 30_000)
        }, delay)
      } else if (user) { void refresh() }
    }
  }
  async function enqueue(change: Pick<Mutation, 'kind' | 'name' | 'sourceId' | 'jobId' | 'chapterId' | 'cardId' | 'action' | 'payload' | 'expectedVersion' | 'file'>) {
    if (!user) throw new Error('请先登录')
    if (!state.value) throw new Error('请先连接服务，取得当前数据后再保存。')
    try {
      await queue.enqueue(user.id, state.value.dataEpoch, change)
      await reload(); void refresh()
    } catch (cause) { error.value = '无法保留待同步内容，请检查浏览器存储空间后重试。'; throw cause }
  }
  async function discard(item: Mutation) { await queue.remove(item.operationId); await reload() }
  async function retrySecurity(item: Mutation) {
    if (item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch || item.error?.code !== 'CSRF_INVALID') return
    await queueStorage.put({ ...item, status: 'pending', nextAttemptAt: 0, error: undefined })
    await reload(); await refresh(true)
  }
  async function resubmit(item: Mutation) {
    if (!user || item.userId !== user.id || item.dataEpoch !== state.value?.dataEpoch) return
    const current = generation
    async function verifyViewed(value: { version: number; annotation?: { version: number } }) {
      const viewed = (item.error?.details as { current?: typeof value } | undefined)?.current
      const version = (v: typeof value) => item.kind === 'card.update' && item.action === 'ANNOTATION' ? v.annotation?.version : v.version
      if (viewed && version(viewed) !== version(value)) {
        const serverDraft = item.kind === 'chapter.review' ? (await request(`/api/drafts/${item.payload?.draftId}`)).data : undefined
        if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return false
        await queueStorage.put({ ...item, error: { code: 'VERSION_CONFLICT', message: '服务端又有修改，核对内容已更新，请再次核对后保存。', details: { current: value, serverDraft } } })
        await reload()
        throw new Error('服务端又有修改，请核对更新后的内容再保存。')
      }
      return true
    }
    if (item.kind === 'preferences.save') {
      const value = (await request<{ version: number }>('/api/preferences')).data
      if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return
      if (!await verifyViewed(value)) return
      await enqueue({ kind: item.kind, name: item.name, payload: item.payload, expectedVersion: value.version })
      await discard(item); return
    }
    if (item.kind === 'card.update') {
      const value = (await request<{ version: number; annotation: { version: number } }>(`/api/cards/${item.cardId}`)).data
      if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return
      if (!await verifyViewed(value)) return
      await enqueue({ kind: item.kind, name: item.name, cardId: item.cardId, action: item.action, payload: item.payload,
        expectedVersion: item.action === 'ANNOTATION' ? value.annotation.version : value.version })
      await discard(item); return
    }
    if (item.kind === 'chapter.review' && item.action === 'EDIT') {
      const value = (await request<{ version: number }>(`/api/chapters/${item.chapterId}/review`)).data
      if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return
      if (!await verifyViewed(value)) return
      await enqueue({ kind: item.kind, name: item.name, chapterId: item.chapterId, action: item.action, payload: item.payload, expectedVersion: value.version })
      await discard(item); return
    }
    if (item.kind !== 'source.rename') return
    const source = await syncApi.source(item.sourceId!)
    if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return
    if (!await verifyViewed(source)) return
    await enqueue({ kind: item.kind, name: item.name, sourceId: source.id, expectedVersion: source.version })
    await discard(item)
  }
  async function resolve(conflict: ReviewConflict, choice: 'CURRENT' | 'CANDIDATE') {
    if (conflict.dataEpoch !== state.value?.dataEpoch || conflict.resolved) return
    const current = generation
    const card = (await request<{ review: { version: number } }>(`/api/cards/${conflict.cardId}`)).data
    if (current !== generation || conflict.dataEpoch !== state.value?.dataEpoch) return
    await enqueue({ kind: 'conflict.resolve', name: choice === 'CURRENT' ? '接受当前评分' : '使用候选更正评分', expectedVersion: conflict.version,
      payload: { conflictId: conflict.id, expectedReviewVersion: card.review.version, choice } })
  }
  return { items, state, error, busy, conflicts, status, notificationState, visibilityChanged, connect, stop, refresh, enqueue, discard, resubmit, retrySecurity, resolve }
})
