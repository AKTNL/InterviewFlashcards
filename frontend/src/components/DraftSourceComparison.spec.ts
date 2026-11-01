import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import DraftSourceComparison from './DraftSourceComparison.vue'
import PdfReader from './PdfReader.vue'
import type { Draft } from '../api/imports'
vi.mock('./PdfReader.vue', () => ({ default: { name: 'PdfReader', props: ['sourceId', 'initialPage', 'bbox', 'libraryId'], template: '<div />' } }))

const draft: Draft = { id: 'd1', sourceId: 'source', question: '跨页', sourceEntry: 'q1', flags: [], excluded: false, version: 2,
  sources: [{ page: 10, bbox: [0, 20, 300, 400] }, { page: 11, bbox: [1, 30, 310, 500] }, { page: 11, bbox: [5, 510, 300, 600] }], contentBlocks: [], followups: [] }

it('selects every physical source span, including separate regions on the same page, in the right namespace', async () => {
  const w = mount(DraftSourceComparison, { props: { draft: structuredClone(draft), libraryId: 'library' } })
  await flushPromises()
  expect(w.findComponent(PdfReader).props()).toMatchObject({ sourceId: 'source', initialPage: 10, bbox: [0, 20, 300, 400], libraryId: 'library' })
  await w.get('select').setValue(2)
  expect(w.findComponent(PdfReader).props()).toMatchObject({ initialPage: 11, bbox: [5, 510, 300, 600] })
  await w.setProps({ draft: { ...draft, id: 'd2' }, unsaved: true })
  expect(w.findComponent(PdfReader).props('initialPage')).toBe(10)
  expect(w.text()).toContain('尚未获服务端确认')
  w.unmount()
})

it('handles removed sources and incomplete boundary edits without showing an invalid highlight', async () => {
  const w = mount(DraftSourceComparison, { props: { draft: structuredClone(draft) } })
  await flushPromises()
  await w.get('select').setValue(2)
  await w.setProps({ draft: { ...draft, sources: [{ page: 12, bbox: [400, 20, 300, 500] }] } })
  expect(w.findComponent(PdfReader).props('initialPage')).toBe(12)
  expect((w.get('select').element as HTMLSelectElement).value).toBe('0')
  expect(w.findComponent(PdfReader).props('bbox')).toBeUndefined()
  expect(w.text()).toContain('边界无效')
  await w.setProps({ draft: { ...draft, sources: [{ page: 0, bbox: [] }] } })
  expect(w.findComponent(PdfReader).exists()).toBe(false)
  expect(w.text()).toContain('没有有效来源页')
  w.unmount()
})
