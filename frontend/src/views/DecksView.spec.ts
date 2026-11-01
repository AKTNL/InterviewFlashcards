import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cardsApi, type DeckOverview } from '../api/cards'
import { useSyncStore } from '../stores/sync'
import DecksView from './DecksView.vue'
vi.mock('../api/cards', () => ({ cardsApi: { decks: vi.fn() } }))
const sample: DeckOverview = { learningDate: '2026-11-01',
  decks: [
    { id: 'a', name: 'Java 面试', total: 25, learned: 3, due: 2, weak: 1, duplicates: 1, chapters: 2, imported: 1, selected: 1 },
    { id: 'b', name: 'Java 面试', total: 1, learned: 0, due: 0, weak: 0, duplicates: 1, chapters: 1, imported: 1, selected: 0 },
    { id: 'empty', name: '待解析', total: 0, learned: 0, due: 0, weak: 0, duplicates: 0, chapters: 0, imported: 0, selected: 0 },
  ], sources: [{ id: 's', deckId: 'b', name: '朋友副本.pdf', sha256: 'abc', sharedCopy: true, jobId: 'job', status: 'READY', sameFileDecks: 1 }] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); vi.mocked(cardsApi.decks).mockResolvedValue(structuredClone(sample)) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const wrapper = mount(DecksView, { global: { stubs: { RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
it('distinguishes same-name decks, empty decks, independent progress and scoped management links', async () => {
  const wrapper = await view(), sections = wrapper.findAll('section[aria-label]')
  expect(sections).toHaveLength(3); expect(sections[0]!.text()).toContain('已学 3/25'); expect(sections[1]!.text()).toContain('已学 0/1')
  expect(sections[2]!.text()).toContain('已确认 0/0'); expect(wrapper.text()).toContain('SHA-256 一致')
  const links = sections[1]!.findAll('a').map(a => JSON.parse(a.attributes('data-to')!))
  expect(links).toContainEqual({ path: '/settings', query: { deckId: 'b' } })
  expect(links).toContainEqual({ path: '/data', query: { deckId: 'b' } })
  expect(links).toContainEqual({ path: '/cards', query: { deckId: 'b', filter: 'DUPLICATE' } })
  await wrapper.get('input').setValue('朋友副本.pdf'); expect(wrapper.findAll('section[aria-label]')).toHaveLength(1)
  await wrapper.get('input').setValue('不存在'); expect(wrapper.text()).toContain('没有符合条件的题库')
})
it('clears stale progress on failed refresh and retries without losing the search', async () => {
  const wrapper = await view(); await wrapper.get('input').setValue('副本')
  vi.mocked(cardsApi.decks).mockRejectedValueOnce(new Error('网络中断'))
  await wrapper.findAll('button').find(b => b.text() === '刷新题库')!.trigger('click'); await flushPromises()
  expect(wrapper.findAll('section[aria-label]')).toHaveLength(0); expect(wrapper.get('[role=alert]').text()).toContain('网络中断')
  await wrapper.findAll('button').find(b => b.text() === '重试')!.trigger('click'); await flushPromises()
  expect(wrapper.findAll('section[aria-label]')).toHaveLength(1)
})
it('refreshes progress after synced scores and removes deleted decks', async () => {
  const wrapper = await view(), sync = useSyncStore()
  sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }; await flushPromises()
  vi.mocked(cardsApi.decks).mockResolvedValue({ ...sample, decks: [] })
  sync.state = { ...sync.state!, revision: 2, changedTables: ['deck'] }; await flushPromises()
  expect(wrapper.text()).toContain('还没有题库'); expect(wrapper.findAll('section[aria-label]')).toHaveLength(0)
})
