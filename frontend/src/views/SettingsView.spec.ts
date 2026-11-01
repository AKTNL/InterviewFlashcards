import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { learningApi, type TodayLearning } from '../api/learning'
import { useSyncStore } from '../stores/sync'
import SettingsView from './SettingsView.vue'
vi.mock('../api/learning', () => ({ learningApi: { today: vi.fn() } }))
const sample: TodayLearning = { learningDate: '2026-10-31', learningTimezone: 'Asia/Shanghai', dueTotal: 0, remainingNew: 5, availableNew: 2,
  preferences: { saved: false, version: 0, dailyNewCount: 5, reviewBatchSize: 20, referenceMinutes: null, chapterIds: [] }, summary: { learned: 0, todayNew: 0, todayReview: 0 },
  chapters: [{ id: 'ch', name: '集合', moduleName: 'Java', deckName: '面试', selected: false, total: 2, learned: 0, due: 0, weak: 0 }], continuing: [] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(learningApi.today).mockResolvedValue(structuredClone(sample)) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia();setActivePinia(pinia);const sync = useSyncStore(), enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }
  const wrapper = mount(SettingsView, { global: { plugins: [pinia], stubs: { RouterLink: true } } });wrappers.push(wrapper);await flushPromises()
  return { wrapper, enqueue, sync }
}
it('lets the user change defaults before first save and persists zero goal, chapter scope and optional minutes', async () => {
  const { wrapper, enqueue } = await view();const inputs = wrapper.findAll('input[type="number"]')
  expect((inputs[0]!.element as HTMLInputElement).value).toBe('5');expect(enqueue).not.toHaveBeenCalled()
  await inputs[0]!.setValue(0);await inputs[1]!.setValue(3);await inputs[2]!.setValue(15);await wrapper.get('input[type="checkbox"]').setValue(true)
  await wrapper.get('form').trigger('submit');expect(enqueue).toHaveBeenCalledWith({ kind: 'preferences.save', name: '每日目标与学习范围', expectedVersion: 0,
    payload: { dailyNewCount: 0, reviewBatchSize: 3, referenceMinutes: 15, chapterIds: ['ch'] } })
  expect(wrapper.text()).toContain('等待服务端确认');expect(wrapper.text()).not.toContain('学习设置已同步')
})
it('blocks invalid batches and retains edits if local persistence fails', async () => {
  const { wrapper, enqueue } = await view();const inputs = wrapper.findAll('input[type="number"]')
  await inputs[1]!.setValue(0);await wrapper.get('form').trigger('submit');expect(enqueue).not.toHaveBeenCalled()
  await inputs[1]!.setValue(2);await inputs[0]!.setValue(0);enqueue.mockRejectedValueOnce(new Error('本地存储失败'))
  await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('本地存储失败');expect((inputs[0]!.element as HTMLInputElement).value).toBe('0')
})
it('refreshes the persisted settings when a restored pending operation is confirmed after reload', async () => {
  const { wrapper, sync } = await view()
  const op = { operationId: 'restored', userId: 'u', dataEpoch: 'epoch', createdAt: 1, kind: 'preferences.save' as const, name: '保存', status: 'pending' as const, attempts: 0, nextAttemptAt: 0 }
  sync.items = [op];await flushPromises();expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  vi.mocked(learningApi.today).mockResolvedValue({ ...sample, preferences: { ...sample.preferences, saved: true, version: 1, dailyNewCount: 0 } })
  sync.items = [{ ...op, status: 'synced' }];await flushPromises()
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('0')
  expect(wrapper.text()).toContain('学习设置已同步')
})

it('keeps unsaved settings when a remote revision arrives and refreshes pristine settings', async () => {
  const { wrapper, sync } = await view()
  await wrapper.get('input[type="number"]').setValue(0)
  vi.mocked(learningApi.today).mockClear()
  sync.state = { dataEpoch: 'epoch', revision: 2, sources: [] }; await flushPromises()
  expect(learningApi.today).not.toHaveBeenCalled(); expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('0')
  expect(wrapper.text()).toContain('本地设置编辑已保留')
})
it('skips unrelated source changes but refreshes chapter statistics after a remote score', async () => {
  const { wrapper, sync } = await view()
  vi.mocked(learningApi.today).mockClear()
  sync.state = { ...sync.state!, revision: 2, changedTables: ['source_document'] }; await flushPromises()
  expect(learningApi.today).not.toHaveBeenCalled()
  vi.mocked(learningApi.today).mockResolvedValue({ ...sample, chapters: [{ ...sample.chapters[0]!, learned: 1, due: 1 }] })
  sync.state = { ...sync.state!, revision: 3, changedTables: ['review_state'] }; await flushPromises()
  expect(wrapper.text()).toContain('已学 1/2 · 到期 1')
})
it('protects unsaved settings from reload and browser close', async () => {
  const { wrapper } = await view();await wrapper.get('input[type="number"]').setValue(0)
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await wrapper.findAll('button').find(b => b.text() === '重新读取设置')!.trigger('click')
  expect(confirm).toHaveBeenCalled();expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('0')
  const event = new Event('beforeunload', { cancelable: true });window.dispatchEvent(event);expect(event.defaultPrevented).toBe(true)
})
it('waits for initial sync before saving and retains edits when the connection becomes ready', async () => {
  const { wrapper, sync, enqueue } = await view()
  sync.state = null; await flushPromises()
  await wrapper.get('input[type="number"]').setValue(0)
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  await wrapper.get('form').trigger('submit'); expect(enqueue).not.toHaveBeenCalled()
  sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }; await flushPromises()
  expect((wrapper.get('input[type="number"]').element as HTMLInputElement).value).toBe('0')
  expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  await wrapper.get('form').trigger('submit')
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ payload: expect.objectContaining({ dailyNewCount: 0 }) }))
})
