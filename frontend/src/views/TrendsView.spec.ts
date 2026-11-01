import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { learningApi, type LearningTrends } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import TrendsView from './TrendsView.vue'
vi.mock('../api/learning', () => ({ learningApi: { trends: vi.fn() } }))
const sample: LearningTrends = { learningDate: '2026-11-01', learningTimezone: 'Asia/Shanghai', from: '2026-09-07', to: '2026-11-01', generatedAt: '2026-11-01T04:00:00Z',
  weeks: [{ weekStart: '2026-10-26', weekEnd: '2026-11-01', learningDays: 2, newCount: 1, reviewCount: 3, assessments: 6, forgot: 2, vague: 1, remembered: 3 }],
  chapters: [{ id: 'ch', name: '集合', moduleName: 'Java', deckName: '面试', selected: false, total: 10, learnedBefore: 3, learned: 4, weakBefore: 2, weak: 1, forgot: 0, vague: 1, remembered: 3, newCount: 1, reviewCount: 3, assessments: 6 }],
  forgotten: { items: [{ id: 'card', question: 'HashMap 原理', chapterName: '集合', moduleName: 'Java', deckName: '面试', lapseCount: 3, latestRating: 'REMEMBERED', dueDate: '2026-11-02' }], total: 21, offset: 0, limit: 20 } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(learningApi.trends).mockImplementation(async (_weeks, offset = 0) => ({ ...structuredClone(sample), forgotten: { ...structuredClone(sample.forgotten), offset } })) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia(); setActivePinia(pinia)
  const w = mount(TrendsView, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } } }); wrappers.push(w); await flushPromises()
  return w
}
it('distinguishes learning volume, replay assessments, chapter changes and lifetime lapse ranking', async () => {
  const w = await view()
  expect(w.text()).toContain('Asia/Shanghai'); expect(w.text()).toContain('2026-09-07 ～ 2026-11-01')
  expect(w.text()).toContain('更新于 2026/11/1 12:00:00（Asia/Shanghai）')
  expect(w.findAll('.today-metrics strong').map(n => n.text())).toEqual(['1', '3', '2', '6'])
  expect(w.text()).toContain('已学 3 → 4（+1）'); expect(w.text()).toContain('薄弱 2 → 1（-1）'); expect(w.text()).toContain('已暂停')
  expect(w.text()).toContain('自评次数包含同日重练'); expect(w.text()).toContain('与上方周数范围无关')
  expect(w.get('a[href="/cards/card"]').text()).toBe('HashMap 原理'); expect(w.text()).toContain('遗忘 3 次 · 最近自评 记住了')
})
it('paginates with server counts and resets to first page after changing weeks', async () => {
  const w = await view(); const button = (text: string) => w.findAll('button').find(b => b.text() === text)!
  expect(button('上一页').attributes('disabled')).toBeDefined()
  await button('下一页').trigger('click'); await flushPromises()
  expect(learningApi.trends).toHaveBeenLastCalledWith(8, 20); expect(button('下一页').attributes('disabled')).toBeDefined()
  await w.get('select').setValue('4'); await flushPromises()
  expect(learningApi.trends).toHaveBeenLastCalledWith(4, 0)
})
it('shows errors without stale metrics and retries an empty history', async () => {
  const w = await view(); vi.mocked(learningApi.trends).mockRejectedValueOnce(new Error('网络中断'))
  await w.get('button').trigger('click'); await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('网络中断'); expect(w.find('table').exists()).toBe(false)
  vi.mocked(learningApi.trends).mockResolvedValue({ ...sample, weeks: [], chapters: [], forgotten: { items: [], total: 0, offset: 0, limit: 20 } })
  await w.get('button').trigger('click'); await flushPromises()
  expect(w.text()).toContain('暂无有效学习记录'); expect(w.text()).toContain('还没有已确认章节'); expect(w.text()).toContain('暂无遗忘次数大于 0')
})
it('refreshes after revision or activation and ignores an older request', async () => {
  const w = await view(); let resolve!: (v: LearningTrends) => void
  vi.mocked(learningApi.trends).mockReturnValueOnce(new Promise(r => { resolve = r }))
  await w.get('button').trigger('click')
  await w.get('select').setValue('12'); await flushPromises()
  resolve({ ...sample, learningTimezone: 'OLD_RESPONSE' }); await flushPromises()
  expect(w.text()).not.toContain('OLD_RESPONSE')
  const sync = useSyncStore(); sync.state = { dataEpoch: 'e', revision: 2, sources: [] }; await flushPromises()
  expect(learningApi.trends).toHaveBeenLastCalledWith(12, 0)
  const before = vi.mocked(learningApi.trends).mock.calls.length; window.dispatchEvent(new Event('focus')); await flushPromises()
  expect(vi.mocked(learningApi.trends).mock.calls.length).toBe(before + 1)
  w.unmount(); const after = vi.mocked(learningApi.trends).mock.calls.length; window.dispatchEvent(new Event('focus')); await flushPromises()
  expect(vi.mocked(learningApi.trends).mock.calls.length).toBe(after)
})
