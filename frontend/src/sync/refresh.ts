import { watch } from 'vue'
import { useSyncStore } from '../stores/sync'

/** Refresh consumers on a server change or learning-day rollover. Editors retain their own dirty guards. */
export function onSyncRefresh(refresh: () => unknown, tables?: string[]) {
  const sync = useSyncStore()
  watch(() => sync.state, (value, previous) => {
    if (!value || (previous && value.dataEpoch === previous.dataEpoch && value.revision === previous.revision && value.learningDate === previous.learningDate)) return
    if (!previous || value.dataEpoch !== previous.dataEpoch || value.learningDate !== previous.learningDate
        || !tables || !value.changedTables || value.changedTables.some(table => tables.includes(table))) void refresh()
  }, { flush: 'sync' })
}
