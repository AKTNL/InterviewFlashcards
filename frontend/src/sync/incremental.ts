import type { Snapshot, Source, SyncChanges } from './types'

/** Build a new state before publishing the cursor. Never mutate the editor's old snapshot. */
export function applyChanges(base: Snapshot, delta: SyncChanges): Snapshot {
  if (delta.fullRequired || delta.dataEpoch !== base.dataEpoch || delta.fromRevision !== base.revision
      || !Number.isSafeInteger(delta.revision) || delta.revision < base.revision || !Array.isArray(delta.changes))
    throw new Error('Invalid sync cursor')
  const data = { ...base.data }
  let sources = base.sources
  const grouped = new Map<string, NonNullable<SyncChanges['changes']>>()
  for (const change of delta.changes) {
    const group = grouped.get(change.table) ?? []
    group.push(change); grouped.set(change.table, group)
  }
  for (const [table, changes] of grouped) {
    const rows = table === 'source_document' ? sources : data[table]
    if (!rows) throw new Error('Unknown sync table')
    const keys = Object.keys(changes[0]!.key).sort()
    const identity = (row: object) => JSON.stringify(keys.map(key => (row as Record<string, unknown>)[key]))
    const indexed = new Map(rows.map(row => [identity(row), row]))
    for (const change of changes) {
      if (!keys.length || Object.keys(change.key).sort().join(',') !== keys.join(',')
          || keys.some(key => typeof change.key[key] !== 'string' && !Number.isSafeInteger(change.key[key]))) throw new Error('Invalid sync identity')
      if (change.row && keys.some(key => change.row![key] !== change.key[key])) throw new Error('Invalid sync row')
      if (change.row) indexed.set(identity(change.key), change.row)
      else indexed.delete(identity(change.key))
    }
    const next = [...indexed.values()]
    if (table === 'source_document') sources = (next as Source[]).sort((a,b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
    else data[table] = next as Record<string, unknown>[]
  }
  return { dataEpoch: delta.dataEpoch, revision: delta.revision, learningDate: delta.learningDate, sources, data, changedTables: [...grouped.keys()] }
}
