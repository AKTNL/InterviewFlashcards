import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import type { Profile } from '../api/auth'
import { ApiError } from '../api/client'
import { queueStorage } from '../sync/database'
import { MutationQueue, syncApi } from '../sync/queue'
import type { Mutation, Snapshot } from '../sync/types'

export const useSyncStore = defineStore('sync', () => {
  const items = shallowRef<Mutation[]>([])
  const state = shallowRef<Snapshot | null>(null)
  const error = ref('')
  const busy = ref(false)
  const queue = new MutationQueue(queueStorage)
  let user: Profile | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  async function reload() {
    const owner = user?.id; const current = generation
    const entries = owner ? await queue.entries(owner) : []
    if (current === generation) items.value = entries
  }
  function stop() {
    user = null; generation++; clearTimeout(timer); items.value = []; state.value = null; error.value = ''
  }
  async function connect(profile: Profile) {
    if (user?.id !== profile.id) { stop(); user = profile }
    try { await reload() }
    catch { error.value = '无法读取本地待同步内容，请检查浏览器存储权限后重试。'; return }
    await refresh()
  }
  async function refresh(force = false) {
    if (!user || busy.value) return
    clearTimeout(timer)
    const current = generation; const owner = user.id
    const active = () => current === generation && user?.id === owner
    busy.value = true; error.value = ''
    try { await reload(); await queue.run(owner, active, reload, value => { if (active()) state.value = value }, force) }
    catch (cause) { if (active()) error.value = cause instanceof ApiError ? cause.message : '本地保存失败，请检查浏览器存储空间；内容尚未确认保存。' }
    finally {
      busy.value = false
      if (active()) {
        const pending = items.value.filter(item => item.status === 'pending' || item.status === 'syncing')
        if (pending.length && !pending.some(item => item.error?.code === 'UNAUTHENTICATED')) {
          const delay = error.value ? 15_000 : Math.max(1000, Math.min(...pending.map(item => item.nextAttemptAt - Date.now())))
          timer = setTimeout(() => { void refresh() }, delay)
        }
      } else if (user) { void refresh() }
    }
  }
  async function enqueue(change: Pick<Mutation, 'kind' | 'name' | 'sourceId' | 'expectedVersion' | 'file'>) {
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
    if (!user || item.userId !== user.id || item.dataEpoch !== state.value?.dataEpoch || item.kind !== 'source.rename') return
    const current = generation
    const source = await syncApi.source(item.sourceId!)
    if (current !== generation || item.userId !== user?.id || item.dataEpoch !== state.value?.dataEpoch) return
    await enqueue({ kind: item.kind, name: item.name, sourceId: source.id, expectedVersion: source.version })
    await discard(item)
  }
  return { items, state, error, busy, connect, stop, refresh, enqueue, discard, resubmit, retrySecurity }
})
