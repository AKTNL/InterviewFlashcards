import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cardsApi, type CardDetail } from '../api/cards'
import { learningApi, type LearningSession } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import PracticeView from './PracticeView.vue'
const { route, replace } = vi.hoisted(() => ({ route: { params: { id: 'session' as string | undefined }, query: { cards: 'card' }, fullPath: '/practice/session' }, replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))
vi.mock('../api/cards', async original => ({ ...await original<typeof import('../api/cards')>(), cardsApi: { detail: vi.fn() } }))
vi.mock('../api/learning', () => ({ learningApi: { session: vi.fn() } }))
const sample: LearningSession = { id: 'session', version: 3, status: 'ACTIVE', learningDate: '2026-10-31', today: '2026-10-31', expired: false, canUndo: true, sameDayReplay: false,
  current: { id: 'item', cardId: 'card', question: 'HashMap', sortOrder: 0, completed: false, skipped: false, isReplay: false, expectedVersion: 0, currentVersion: 0 },
  items: [], events: [], review: null }
const card = { id: 'card', question: 'HashMap', moduleName: 'Java', chapterName: '集合', needsCheck: true, contentBlocks: [{ type: 'MANUAL_TEXT', page: 1, textSpans: [{ text: '完整答案尾部', bbox: [] }], images: [] }],
  annotation: { hint: '隐藏提示', memoryPoints: '要点', notes: '' }, followups: [], sources: [] } as unknown as CardDetail
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); route.params.id = 'session'; route.fullPath = '/practice/session'; vi.mocked(learningApi.session).mockResolvedValue(structuredClone(sample)); vi.mocked(cardsApi.detail).mockResolvedValue(card) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks() })
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore(); const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const wrapper = mount(PracticeView, { global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } }); wrappers.push(wrapper); await flushPromises()
  return { wrapper, sync, enqueue, button: (text: string) => wrapper.findAll('button').find(b => b.text() === text)! }
}
it('hides ratings and answer until revealed; viewing hints never submits', async () => {
  const { wrapper, enqueue, button } = await view(); expect(wrapper.text()).not.toContain('完整答案尾部');expect(button('记住了')).toBeUndefined()
  await button('查看提示与要点').trigger('click');expect(wrapper.text()).toContain('隐藏提示');expect(enqueue).not.toHaveBeenCalled()
  await button('展开完整答案').trigger('click');expect(wrapper.text()).toContain('完整答案尾部');expect(button('不会')).toBeDefined();expect(wrapper.text()).toContain('待检查')
})
it('binds score to session/item/state versions and waits for confirmation before advancing', async () => {
  const { wrapper, sync, enqueue, button } = await view();await button('展开完整答案').trigger('click');await button('不会').trigger('click');await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ kind: 'review.write', action: 'RATE', expectedVersion: 3,
    payload: expect.objectContaining({ sessionId: 'session', itemId: 'item', expectedReviewVersion: 0, rating: 'FORGOT', answerRevealed: true }) }))
  expect(wrapper.text()).toContain('HashMap');expect(wrapper.text()).not.toContain('本轮练习结束')
  const change = enqueue.mock.calls[0]![0]
  sync.items = [{ ...change, operationId: 'op', userId: 'user', dataEpoch: 'epoch', createdAt: 1, status: 'pending', attempts: 0, nextAttemptAt: 0 }];await flushPromises()
  expect(button('不会').attributes('disabled')).toBeDefined()
  vi.mocked(learningApi.session).mockResolvedValue({ ...sample, version: 4, status: 'COMPLETED', current: null })
  sync.items = [{ ...sync.items[0]!, status: 'synced', result: { dueDate: '2026-11-01', session: { ...sample, version: 4, status: 'COMPLETED', current: null }, mutation: { operationId: 'op', dataEpoch: 'epoch', entityVersion: 4, revision: 9, serverTime: '' } } }];await flushPromises()
  expect(wrapper.text()).toContain('下次复习：2026-11-01');expect(wrapper.text()).toContain('本轮练习结束')
})
it('keeps revealed answer after local storage failure and allows retry', async () => {
  const { wrapper, enqueue, button } = await view();enqueue.mockRejectedValueOnce(new Error('存储失败'))
  await button('展开完整答案').trigger('click');await button('模糊').trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('存储失败');expect(wrapper.text()).toContain('完整答案尾部');expect(button('模糊').attributes('disabled')).toBeUndefined()
})
it('supports skipping automatic replay and confirms relearn impact', async () => {
  const s = structuredClone(sample); s.current!.isReplay = true;s.review = { firstLearnedAt: '2026-01-01T00:00:00Z', lastReviewedAt: '2026-10-31T00:00:00Z', dueDate: '2026-11-01', stage: -1, latestRating: 'FORGOT', lapseCount: 1, version: 0 }
  vi.mocked(learningApi.session).mockResolvedValue(s)
  const { enqueue, button } = await view();await button('跳过重练').trigger('click');expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'SKIP' }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);await button('重新学习此题').trigger('click');expect(enqueue).toHaveBeenCalledTimes(1)
  confirm.mockReturnValue(true);await button('重新学习此题').trigger('click');expect(enqueue).toHaveBeenLastCalledWith(expect.objectContaining({ action: 'RELEARN', payload: expect.objectContaining({ confirmed: true }) }))
})
it('permits undo after completed round and submits original latest event', async () => {
  vi.mocked(learningApi.session).mockResolvedValue({ ...sample, current: null, status: 'COMPLETED', events: [{ id: 'event', cardId: 'card', kind: 'RATE', rating: 'REMEMBERED', isReplay: false, afterState: {} as never }] })
  const { enqueue, button } = await view();await button('撤销最近一次评分').trigger('click');expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'UNDO', payload: expect.objectContaining({ eventId: 'event' }) }))
})
it('blocks scores after day rollover or a newer review version', async () => {
  const s = structuredClone(sample);s.expired = true;vi.mocked(learningApi.session).mockResolvedValue(s)
  const { wrapper, button } = await view();await button('展开完整答案').trigger('click');expect(button('记住了').attributes('disabled')).toBeDefined();expect(wrapper.text()).toContain('跨天')
})
it('requires explicit start and persists selected card IDs through the queue', async () => {
  route.params.id = undefined;route.fullPath = '/practice?cards=card'
  const { enqueue, button } = await view();expect(enqueue).not.toHaveBeenCalled();await button('开始主动回忆').trigger('click')
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ kind: 'session.start', payload: { cardIds: ['card'] } }))
})

it('refreshes remote session advancement when a server revision arrives', async () => {
  const { wrapper, sync } = await view()
  vi.mocked(learningApi.session).mockResolvedValue({ ...sample, current: null, status: 'COMPLETED' })
  sync.state = { dataEpoch: 'epoch', revision: 8, sources: [] }; await flushPromises()
  expect(wrapper.text()).toContain('本轮练习结束')
})

it('uses the latest server session after recovering an older successful receipt', async () => {
  const { wrapper, sync } = await view()
  vi.mocked(learningApi.session).mockResolvedValue({ ...sample, version: 9, current: null, status: 'COMPLETED' })
  sync.items = [{ operationId: 'recovered', userId: 'user', dataEpoch: 'epoch', createdAt: 1, kind: 'review.write', name: '恢复评分', action: 'RATE',
    payload: { sessionId: 'session' }, status: 'synced', attempts: 0, nextAttemptAt: 0,
    result: { session: { ...sample, version: 4 }, dueDate: '2026-11-01', mutation: { operationId: 'recovered', dataEpoch: 'epoch', entityVersion: 4, revision: 4, serverTime: '' } } }]
  await flushPromises(); expect(wrapper.text()).toContain('本轮练习结束'); expect(wrapper.text()).not.toContain('HashMap')
})
