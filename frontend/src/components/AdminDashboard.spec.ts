import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AdminDashboard from './AdminDashboard.vue'
import { overview, usageUsers, usageDetail } from '../api/dashboard'
import { ApiError } from '../api/client'
import { useAuthStore } from '../stores/auth'
vi.mock('../api/dashboard', () => ({ overview: vi.fn(), usageUsers: vi.fn(), usageDetail: vi.fn() }))
const meta = { requestId: 'test', serverTime: '2026-11-01T04:00:00Z' }
const info = { timezone: 'Asia/Shanghai', from: '2026-10-31', to: '2026-11-01', updatedAt: meta.serverTime, collectionStartedAt: '2026-11-01T00:00:00Z', retainedFrom: '2025-11-01', activityCoverage: 'PARTIAL' as const }
const days = [{ day: '2026-10-31', active_users: null, learning_users: 1, seconds: null, new_count: 2, review_count: 0 }, { day: '2026-11-01', active_users: 0, learning_users: 0, seconds: 0, new_count: 0, review_count: 0 }]
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia()); vi.spyOn(useAuthStore(), 'refresh').mockResolvedValue(undefined)
  vi.mocked(overview).mockResolvedValue({ data: { ...info, summary: { users: 12, enabled: 11, disabled: 1, seconds: 0 }, days }, meta })
  vi.mocked(usageUsers).mockResolvedValue({ data: { ...info, total: 12, items: [{ id: 'a', username: 'zero', nickname: null, enabled: true, last_login_at: null, last_active_at: null, online: false, active_days: 0, seconds: 0, new_count: 0, review_count: 0, claims: 0, storage_bytes: 0 }] }, meta })
  vi.mocked(usageDetail).mockResolvedValue({ data: { ...info, user: { id: 'a', username: 'zero', nickname: null }, days }, meta })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks() })
async function view() { const w = mount(AdminDashboard); wrappers.push(w); await flushPromises(); return w }
it('distinguishes uncollected history and real zero and shows scope, timezone and zero-use accounts', async () => {
  const w = await view(); expect(w.text()).toContain('部分时段尚未采集'); expect(w.text()).toContain('Asia/Shanghai'); expect(w.text()).toContain('时长采集起点'); expect(w.text()).toContain('zero'); expect(w.text()).toContain('0.0 分钟'); expect(w.text()).toContain('尚无记录')
  expect(w.findAll('tbody tr')[0]!.text()).toContain('未采集'); expect(w.findAll('tbody tr')[1]!.text()).toContain('0.0 分钟')
})
it('sends bounded filters and paging and uses the applied range for per-user details', async () => {
  const w = await view(); await w.get('input[type=date]').setValue('2026-10-31'); await w.findAll('input')[1]!.setValue('2026-11-01'); await w.get('input[maxlength]').setValue('zero'); await w.get('select').setValue('duration'); await w.get('form').trigger('submit'); await flushPromises()
  const params = vi.mocked(usageUsers).mock.calls.at(-1)![0]; expect(params.get('from')).toBe('2026-10-31'); expect(params.get('search')).toBe('zero'); expect(params.get('sort')).toBe('duration')
  await w.findAll('button').find(b => b.text() === '下一页')!.trigger('click'); await flushPromises(); expect(vi.mocked(usageUsers).mock.calls.at(-1)![0].get('page')).toBe('1')
  await w.get('input[type=date]').setValue('2026-10-01'); await w.findAll('button').find(b => b.text().includes('查看 zero'))!.trigger('click'); await flushPromises(); expect(vi.mocked(usageDetail).mock.calls.at(-1)![1].get('from')).toBe('2026-10-31'); expect(w.text()).toContain('zero 的每日汇总')
})
it('clears stale data after rejected live authorization and refreshes identity', async () => {
  const w = await view(); vi.mocked(overview).mockRejectedValue(new ApiError('FORBIDDEN', '权限已回收', 403)); await w.get('form').trigger('submit'); await flushPromises()
  expect(w.text()).toContain('权限已回收'); expect(w.text()).not.toContain('查看 zero'); expect(useAuthStore().refresh).toHaveBeenCalled()
})
it('discards a delayed selected-user result after the filters are refreshed', async () => {
  const w = await view(); let resolve!: (v: Awaited<ReturnType<typeof usageDetail>>) => void
  vi.mocked(usageDetail).mockReturnValueOnce(new Promise(r => { resolve = r }))
  await w.findAll('button').find(b => b.text().includes('查看 zero'))!.trigger('click'); await w.get('form').trigger('submit'); await flushPromises()
  resolve({ data: { ...info, user: { id: 'a', username: 'stale', nickname: null }, days }, meta }); await flushPromises(); expect(w.text()).not.toContain('stale')
})
