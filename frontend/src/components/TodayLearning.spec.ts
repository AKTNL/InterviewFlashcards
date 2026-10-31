import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { learningApi, type TodayLearning as Today } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import TodayLearning from './TodayLearning.vue'
const { push } = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../api/learning', async original => ({ ...await original<typeof import('../api/learning')>(), learningApi: { today: vi.fn() } }))
const sample: Today = { learningDate: '2026-10-31', learningTimezone: 'Asia/Shanghai', dueTotal: 43, remainingNew: 0, availableNew: 8,
  preferences: { saved: true, version: 1, dailyNewCount: 0, reviewBatchSize: 20, referenceMinutes: null, chapterIds: ['ch'] },
  summary: { learned: 50, todayNew: 2, todayReview: 3 }, chapters: [{ id: 'ch', name: '集合', moduleName: 'Java', deckName: '面试', selected: true, total: 60, learned: 50, due: 43, weak: 5 }],
  continuing: [{ id: 'session', mode: 'REVIEW', remaining: 7 }] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(learningApi.today).mockResolvedValue(structuredClone(sample)) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore(), enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const wrapper = mount(TodayLearning, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } }); wrappers.push(wrapper); await flushPromises()
  return { wrapper, sync, enqueue, button: (text: string) => wrapper.findAll('button').find(b => b.text() === text)! }
}
it('shows total due independently of batch and zero new goal still allows review', async () => {
  const { wrapper, enqueue, button } = await view()
  expect(wrapper.text()).toContain('43');expect(wrapper.text()).toContain('每轮最多复习 20');expect(wrapper.text()).toContain('2 / 0')
  expect(wrapper.text()).toContain('今日剩余新题额度 0');expect(wrapper.text()).toContain('继续只复习 · 剩余 7 项')
  expect(wrapper.get('a[href="/practice/session"]').attributes('href')).toBe('/practice/session')
  await button('只复习').trigger('click');expect(enqueue).toHaveBeenCalledWith({ kind: 'session.daily', name: '只复习', payload: { mode: 'REVIEW' } });expect(push).not.toHaveBeenCalled()
})
it('requires explicit preferences and routes only after a confirmed daily session receipt', async () => {
  vi.mocked(learningApi.today).mockResolvedValue({ ...sample, preferences: { ...sample.preferences, saved: false } })
  const { wrapper, sync, button } = await view();expect(wrapper.text()).toContain('先到学习设置');expect(button('混合学习').attributes('disabled')).toBeDefined()
  vi.mocked(learningApi.today).mockResolvedValue(structuredClone(sample));await button('刷新今日计划').trigger('click');await flushPromises();await button('只复习').trigger('click')
  sync.items = [{ operationId: 'op', userId: 'u', dataEpoch: 'epoch', createdAt: Date.now(), kind: 'session.daily', name: '复习', payload: { mode: 'REVIEW' }, status: 'synced', attempts: 0, nextAttemptAt: 0,
    result: { session: { id: 'confirmed' } as never, mutation: { operationId: 'op', dataEpoch: 'epoch', revision: 2, entityVersion: 0, serverTime: '' } } }]
  await flushPromises();expect(push).toHaveBeenCalledWith('/practice/confirmed')
})
it('stays on the home page when IndexedDB loads historical confirmed session receipts', async () => {
  const { sync, wrapper } = await view()
  sync.items = [{ operationId: 'old', userId: 'u', dataEpoch: 'epoch', createdAt: 1, kind: 'session.daily', name: '旧计划', status: 'synced', attempts: 0, nextAttemptAt: 0,
    result: { session: { id: 'old-session' } as never, mutation: { operationId: 'old', dataEpoch: 'epoch', revision: 1, entityVersion: 0, serverTime: '' } } }]
  await flushPromises();expect(push).not.toHaveBeenCalled();expect(wrapper.text()).toContain('今天，从一次回忆开始。')
})
it('keeps server date and refreshes actionable counts after activation, with retry on failure', async () => {
  vi.mocked(learningApi.today).mockRejectedValueOnce(new Error('连接失败'))
  const { wrapper, button } = await view();expect(wrapper.text()).toContain('连接失败')
  await button('刷新今日计划').trigger('click');await flushPromises();expect(wrapper.text()).toContain('2026-10-31 · Asia/Shanghai')
  vi.mocked(learningApi.today).mockResolvedValue({ ...sample, dueTotal: 9 });window.dispatchEvent(new Event('focus'));await flushPromises()
  expect(wrapper.get('.today-metrics strong').text()).toBe('9')
})
