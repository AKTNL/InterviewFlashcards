import { watch } from 'vue'
import { useSyncStore } from '../stores/sync'

/** Refresh consumers on a server change or learning-day rollover. Editors retain their own dirty guards. */
export function onSyncRefresh(refresh: () => unknown) {
  const sync = useSyncStore()
  watch(() => sync.state && `${sync.state.dataEpoch}:${sync.state.revision}:${sync.state.learningDate ?? ''}`, value => {
    if (value) void refresh()
  })
}
