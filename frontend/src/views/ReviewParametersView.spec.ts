import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ReviewParametersView from './ReviewParametersView.vue'
import { reviewParametersApi, type ReviewReport } from '../api/reviewParameters'
import { ApiError } from '../api/client'
import { useSyncStore } from '../stores/sync'
vi.mock('../api/reviewParameters', () => ({ reviewParametersApi: { report: vi.fn(), save: vi.fn() } }))
const sample: ReviewReport = { current: { version: 0, algorithmVersion: 'rules-v1', intervals: [1, 3, 7, 14, 30], reason: '默认', createdAt: null },
  history: [], activity: [], outcomes: [], from: '2026-08-04', to: '2026-11-01', learningTimezone: 'Asia/Shanghai', generatedAt: '2026-11-01T04:00:00Z' }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(reviewParametersApi.report).mockResolvedValue(structuredClone(sample)) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks() })
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore()
  sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }
  const w = mount(ReviewParametersView, { global: { plugins: [pinia], stubs: { RouterLink: true } } }); wrappers.push(w); await flushPromises()
  return { w, sync }
}
it('explains unchanged dates, attribution, empty samples and reasoned parameter changes', async () => {
  const { w } = await view()
  expect(w.text()).toContain('不批量重算已有到期日'); expect(w.text()).toContain('暂无可评估的到期复习样本')
  expect(w.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  await w.get('input').setValue(2); await w.get('textarea').setValue('观察效果')
  const next = { ...sample.current, version: 1, algorithmVersion: 'personal-new', intervals: [2, 3, 7, 14, 30], reason: '观察效果' }
  vi.mocked(reviewParametersApi.save).mockResolvedValue({ current: next }); vi.mocked(reviewParametersApi.report).mockResolvedValue({ ...sample, current: next, history: [next] })
  await w.get('form').trigger('submit'); await flushPromises()
  expect(reviewParametersApi.save).toHaveBeenCalledWith(expect.objectContaining({ dataEpoch: 'epoch', expectedVersion: 0, intervals: next.intervals, reason: '观察效果' }))
  expect(w.text()).toContain('复习参数已保存'); expect(w.text()).toContain('personal-new')
})
it('rejects decreasing or out-of-bounds intervals and missing reasons, waits for sync identity', async () => {
  const { w, sync } = await view()
  await w.get('input').setValue(4); await w.get('textarea').setValue('原因'); await w.get('form').trigger('submit')
  expect(reviewParametersApi.save).not.toHaveBeenCalled()
  await w.get('input').setValue(366); expect(w.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  await w.get('input').setValue(2); sync.state = null; await flushPromises(); await w.get('form').trigger('submit')
  expect(reviewParametersApi.save).not.toHaveBeenCalled()
})
it('retries a lost response using the identical immutable operation', async () => {
  const { w } = await view(); await w.get('input').setValue(2); await w.get('textarea').setValue('原因')
  vi.mocked(reviewParametersApi.save).mockRejectedValue(new ApiError('NETWORK_ERROR', '响应丢失', 0))
  await w.get('form').trigger('submit'); await flushPromises()
  const body = JSON.parse(JSON.stringify(vi.mocked(reviewParametersApi.save).mock.calls[0]![0]))
  expect(w.text()).toContain('重试确认保存'); expect(w.get('input').attributes('disabled')).toBeUndefined() // disabled by fieldset
  expect(w.get('fieldset').attributes('disabled')).toBeDefined()
  await w.get('form').trigger('submit'); await flushPromises()
  expect(reviewParametersApi.save).toHaveBeenLastCalledWith(body)
})
it('keeps conflicting edits for comparison and requires an explicit reload', async () => {
  const { w, sync } = await view(); await w.get('input').setValue(2); await w.get('textarea').setValue('原因')
  vi.mocked(reviewParametersApi.save).mockRejectedValue(new ApiError('VERSION_CONFLICT', '版本冲突', 409))
  await w.get('form').trigger('submit'); await flushPromises()
  expect(w.text()).toContain('请重新读取、核对后再修改')
  expect((w.get('input').element as HTMLInputElement).value).toBe('2')
  sync.state = { dataEpoch: 'epoch', revision: 2, sources: [] }; await flushPromises()
  expect((w.get('input').element as HTMLInputElement).value).toBe('2')
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  await w.findAll('button').find(b => b.text() === '重新读取参数与记录')!.trigger('click'); await flushPromises()
  expect((w.get('input').element as HTMLInputElement).value).toBe('1')
})
it('displays observation denominators, legacy unknown parameters, late counts and separate activity', async () => {
  vi.mocked(reviewParametersApi.report).mockResolvedValue({ ...sample,
    outcomes: [{ algorithmVersion: 'legacy', parameters: {}, observations: 4, cards: 2, forgot: 1, vague: 1, remembered: 2, onTime: 3, late: 1, averageLateDays: 0.5, averagePlannedDays: 3 }],
    activity: [{ algorithmVersion: 'rules-v1', assessments: 8, firstAssessments: 3, replays: 1 }] })
  const { w } = await view(); expect(w.text()).toContain('历史参数未记录'); expect(w.text()).toContain('50.0%')
  expect(w.text()).toContain('4 / 2'); expect(w.text()).toContain('首次 3 次、同日重练 1 次')
  await w.get('select').setValue('30'); await flushPromises(); expect(reviewParametersApi.report).toHaveBeenLastCalledWith(30)
})
it('offers retry after a read failure and protects unsaved input on browser close', async () => {
  vi.mocked(reviewParametersApi.report).mockRejectedValueOnce(new Error('离线'))
  const { w } = await view(); expect(w.text()).toContain('离线')
  await w.get('button').trigger('click'); await flushPromises(); expect(w.find('form').exists()).toBe(true)
  await w.get('input').setValue(2)
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true)
})
