import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cardsApi } from '../api/cards'
import CardsView from './CardsView.vue'
const mocks = vi.hoisted(() => ({ route: { query: {} as Record<string, string>, params: {} as Record<string, string>, fullPath: '/cards' }, push: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => reactive(mocks.route), useRouter: () => ({ push: mocks.push }) }))
vi.mock('../api/cards', async original => ({ ...await original<typeof import('../api/cards')>(), cardsApi: { catalog: vi.fn(), list: vi.fn() } }))
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks();mocks.route.query = {};mocks.route.params = {}
  vi.mocked(cardsApi.catalog).mockResolvedValue([{ deckId: 'deck', deckName: '面试', moduleId: 'java', moduleName: 'Java', chapterId: 'chapter', chapterName: '集合', checkStatus: 'IMPORTED', cardCount: 21 }])
  vi.mocked(cardsApi.list).mockResolvedValue({ items: [{ id: 'card', question: 'HashMap', moduleName: 'Java', chapterName: '集合', needsCheck: true, favorite: true, learned: false, dueDate: null, latestRating: null }], total: 21, offset: 0, limit: 20, learningDate: '2026-10-31' })
})
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() { const wrapper = mount(CardsView, { global: { stubs: { RouterLink: true } } });wrappers.push(wrapper);await flushPromises();return wrapper }
it('retains check reminders and sends scoped literal search and favorite filter with pagination', async () => {
  const wrapper = await view();expect(wrapper.text()).toContain('待检查');expect(wrapper.text()).toContain('已收藏')
  await wrapper.get('input').setValue('100%_');const selects = wrapper.findAll('select');await selects[0]!.setValue('deck');await selects[1]!.setValue('java');await selects[2]!.setValue('chapter');await selects[3]!.setValue('FAVORITE');await wrapper.get('form').trigger('submit')
  expect(mocks.push).toHaveBeenLastCalledWith({ path: '/cards', query: { q: '100%_', deckId: 'deck', moduleId: 'java', chapterId: 'chapter', filter: 'FAVORITE', offset: '0' } })
  await wrapper.findAll('button').find(b => b.text() === '下一页')!.trigger('click');expect(mocks.push).toHaveBeenLastCalledWith(expect.objectContaining({ query: expect.objectContaining({ offset: '20' }) }))
})
it('offers clear conditions for no results and resets scope and offset', async () => {
  vi.mocked(cardsApi.list).mockResolvedValue({ items: [], total: 0, offset: 0, limit: 20, learningDate: '2026-10-31' });mocks.route.query = { q: '无结果', offset: '20', filter: 'WEAK' }
  const wrapper = await view();expect(wrapper.text()).toContain('没有符合条件的题目')
  await wrapper.findAll('button').find(b => b.text() === '清除条件')!.trigger('click');expect(mocks.push).toHaveBeenLastCalledWith({ path: '/cards', query: { filter: 'ALL', offset: '0' } })
})
it('keeps chapter entry compatible and displays a retryable service failure', async () => {
  mocks.route.params = { id: 'chapter' };vi.mocked(cardsApi.list).mockRejectedValue(new Error('服务不可用'))
  const wrapper = await view();expect(vi.mocked(cardsApi.list).mock.calls[0]![0].get('chapterId')).toBe('chapter');expect(wrapper.get('[role=alert]').text()).toContain('服务不可用')
})
