import { expect, it } from 'vitest'
import { applyChanges } from './incremental'
import type { Snapshot, SyncChanges } from './types'

const base: Snapshot = { dataEpoch: 'epoch', revision: 4, learningDate: '2026-11-01', sources: [], data: {
  card: [{ id: 'keep', question: '保留' }, { id: 'delete', question: '删除' }],
  card_tag: [{ card_id: 'keep', tag_id: 'old' }], review_policy: [{ version: 1 }],
} }
const delta: SyncChanges = { dataEpoch: 'epoch', fromRevision: 4, revision: 7, learningDate: '2026-11-01', fullRequired: false, changes: [
  { table: 'card', key: { id: 'keep' }, row: { id: 'keep', question: '远端修正' } },
  { table: 'card', key: { id: 'delete' }, row: null },
  { table: 'card_tag', key: { card_id: 'keep', tag_id: 'old' }, row: null },
  { table: 'card_tag', key: { card_id: 'keep', tag_id: 'new' }, row: { card_id: 'keep', tag_id: 'new' } },
  { table: 'review_policy', key: { version: 2 }, row: { version: 2 } },
] }
it('atomically applies disconnected revisions, composite deletions and numeric policy keys without changing the old snapshot', () => {
  const next = applyChanges(base, delta)
  expect(next.revision).toBe(7)
  expect(next.data?.card).toEqual([{ id: 'keep', question: '远端修正' }])
  expect(next.data?.card_tag).toEqual([{ card_id: 'keep', tag_id: 'new' }])
  expect(next.data?.review_policy).toEqual([{ version: 1 }, { version: 2 }])
  expect(base.data?.card).toHaveLength(2); expect(base.revision).toBe(4)
})
it.each([
  { dataEpoch: 'restored' }, { fromRevision: 3 }, { revision: 2 }, { fullRequired: true },
  { changes: [{ table: 'unknown', key: { id: 'x' }, row: null }] },
  { changes: [{ table: 'card', key: { id: 'keep' }, row: { id: 'other' } }] },
])('rejects an unusable delta before publishing its cursor: %j', invalid => {
  expect(() => applyChanges(base, { ...delta, ...invalid })).toThrow()
  expect(base.data?.card).toHaveLength(2)
})
it('applies source names in API shape and authoritative source deletions', () => {
  const source = { id: 's', version: 0, originalFilename: 'a.pdf', createdAt: '2026-11-01T00:00:00Z', pageCount: 1, byteSize: 10 }
  const next = applyChanges({ ...base, sources: [source] }, { ...delta, changes: [
    { table: 'source_document', key: { id: 's' }, row: null },
    { table: 'source_document', key: { id: 'new' }, row: { ...source, id: 'new', originalFilename: '新.pdf' } },
  ] })
  expect(next.sources.map(s => s.originalFilename)).toEqual(['新.pdf'])
})
