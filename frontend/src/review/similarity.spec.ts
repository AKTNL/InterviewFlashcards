import { expect, it } from 'vitest'
import type { DraftSummary } from '../api/imports'
import { normalizeTitle, similarDrafts, titleSimilarity } from './similarity'

it('recognizes title variants while keeping technical numbers meaningful', () => {
  expect(normalizeTitle(' １２、HashMap 底层原理？')).toBe('hashmap底层原理')
  expect(titleSimilarity('1. HashMap 底层原理？', 'hashmap 底层原理')).toBe(1)
  expect(normalizeTitle('Java 8 与 Java 21')).toBe('java8与java21')
  expect(titleSimilarity('HashMap底层原理和扩容机制', 'HashMap的底层原理和扩容机制')).toBeGreaterThanOrEqual(.65)
  expect(titleSimilarity('如何解决数据库死锁', 'Java垃圾回收算法')).toBeLessThan(.65)
  expect(titleSimilarity('什么是', '什么叫')).toBe(0)
  expect(titleSimilarity('', '')).toBe(0)
  expect(titleSimilarity('aaaa', 'aaaaaaaaaa')).toBeLessThan(.65)
})

it('excludes self and excluded rows and sorts suggestions without mutating the inputs', () => {
  const current: DraftSummary = { id: 'current', question: 'HashMap底层原理和扩容机制', sourceEntry: 'q1', flags: [], excluded: false, version: 3 }
  const rows = [current, { ...current, id: 'similar', question: 'HashMap的底层原理和扩容机制' }, { ...current, id: 'exact' }, { ...current, id: 'excluded', excluded: true }]
  const before = structuredClone(rows)
  expect(similarDrafts(current, rows).map(m => m.item.id)).toEqual(['exact', 'similar'])
  expect(similarDrafts({ ...current, excluded: true }, rows)).toEqual([])
  expect(rows).toEqual(before)
})
