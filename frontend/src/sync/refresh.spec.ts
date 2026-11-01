import { createPinia, setActivePinia } from 'pinia'
import { effectScope } from 'vue'
import { expect, it, vi } from 'vitest'
import { useSyncStore } from '../stores/sync'
import { onSyncRefresh } from './refresh'

it('refreshes relevant consumers, retains rapid successive changes, and always refreshes on replacement or a new learning day', () => {
  setActivePinia(createPinia())
  const store = useSyncStore(), refresh = vi.fn(), scope = effectScope()
  scope.run(() => onSyncRefresh(refresh, ['card']))
  const initial = { dataEpoch: 'epoch', revision: 1, learningDate: '2026-11-01', sources: [], data: { card: [] } }
  store.state = initial; expect(refresh).toHaveBeenCalledTimes(1)
  store.state = { ...initial, revision: 2, changedTables: ['source_document'] }; expect(refresh).toHaveBeenCalledTimes(1)
  store.state = { ...initial, revision: 3, changedTables: ['card'] }
  store.state = { ...initial, revision: 4, changedTables: ['import_job'] }; expect(refresh).toHaveBeenCalledTimes(2)
  store.state = { ...initial, revision: 4, learningDate: '2026-11-02', changedTables: [] }; expect(refresh).toHaveBeenCalledTimes(3)
  store.state = { ...initial, revision: 5 }; expect(refresh).toHaveBeenCalledTimes(4)
  store.state = { ...initial, revision: 5, dataEpoch: 'restored', changedTables: [] }; expect(refresh).toHaveBeenCalledTimes(5)
  scope.stop(); store.stop()
})
