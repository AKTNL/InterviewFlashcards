import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AdminView from './AdminView.vue'
import { adminWrite, getAdminUsers, getAdminDetail, getAdminConfiguration, type AdminDetail } from '../api/admin'
import { ApiError } from '../api/client'
import { useAuthStore } from '../stores/auth'
vi.mock('../api/admin', () => ({ adminWrite: vi.fn(), getAdminUsers: vi.fn(), getAdminDetail: vi.fn(), getAdminConfiguration: vi.fn() }))
const meta = { requestId: 'test', serverTime: '2026-11-01T00:00:00Z' }
const user: AdminDetail = { id: 'target', username: 'friend', nickname: '朋友', role: 'USER', enabled: true, created_at: '2026-11-01T00:00:00Z', last_login_at: null, account_origin: 'ADMIN_CREATED', must_change_password: false, admin_version: 3, invitation_enabled: false, total_limit: 3, successful_count: 1, remaining_quota: 2, storage: { usedBytes: 0, maxBytes: 100, sources: 0 }, invites: [], audit: [] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia()); vi.spyOn(useAuthStore(), 'refresh').mockResolvedValue(undefined)
  vi.mocked(getAdminUsers).mockResolvedValue({ data: { items: [structuredClone(user)], total: 1 }, meta })
  vi.mocked(getAdminDetail).mockResolvedValue({ data: structuredClone(user), meta })
  vi.mocked(getAdminConfiguration).mockResolvedValue({ data: { registrationEnabled: false, maxAccounts: 100, allocatedAccounts: 5, configurationChannel: '环境配置；页面只读', capacity: { userBytes: 100, sources: 20, imports: 2, backups: 2, globalTasks: 20, globalBytes: 1000, reserveBytes: 100 } }, meta })
  vi.mocked(adminWrite).mockResolvedValue({ data: true, meta })
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.useRealTimers() })
async function view(selected = false) { const w = mount(AdminView); wrappers.push(w); await flushPromises(); if (selected) { await w.findAll('button').find(b => b.text() === '管理与审计')!.trigger('click'); await flushPromises() } return w }
it('queries by name status and permission and distinguishes uncollected metrics', async () => {
  const w = await view(); await w.get('.admin-filters input').setValue('friend'); await w.get('.admin-filters select').setValue('DISABLED'); await w.get('.admin-filters').trigger('submit'); await flushPromises()
  expect(vi.mocked(getAdminUsers).mock.calls.at(-1)![0].get('search')).toBe('friend')
  expect(vi.mocked(getAdminUsers).mock.calls.at(-1)![0].get('state')).toBe('DISABLED')
  await w.findAll('button').find(b => b.text() === '权限管理')!.trigger('click'); expect(w.text()).toContain('注册：关闭'); expect(w.text()).toContain('页面只读')
  await w.findAll('button').find(b => b.text() === 'Dashboard')!.trigger('click'); expect(w.text()).toContain('在线时长尚未采集')
})
it('requires confirmation and the current version for disabling and permission changes', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false); const w = await view(true)
  await w.findAll('button').find(b => b.text() === '停用用户')!.trigger('click'); expect(adminWrite).not.toHaveBeenCalled()
  confirm.mockReturnValue(true); await w.get('[aria-labelledby=target-title] form').trigger('submit'); await flushPromises()
  expect(adminWrite).toHaveBeenCalledWith('users/target/permission', { version: 3, enabled: false, totalLimit: 3 })
})
it('does not replay writes after response loss and reuses the explicit creation identifier', async () => {
  const w = await view(); vi.mocked(adminWrite).mockRejectedValue(new ApiError('NETWORK_ERROR', '网络中断', 0))
  const form = w.get('section.admin-box:last-child form'); const fields = form.findAll('input')
  await fields[0]!.setValue('friend_new'); await fields[2]!.setValue('test-initial-password'); await form.trigger('submit'); await flushPromises()
  expect(adminWrite).toHaveBeenCalledTimes(1); expect((fields[2]!.element as HTMLInputElement).value).toBe(''); expect(w.text()).toContain('先刷新并按用户名查询结果')
  await fields[2]!.setValue('test-initial-password'); await form.trigger('submit'); await flushPromises()
  expect((vi.mocked(adminWrite).mock.calls[0]![1] as { operationId: string }).operationId).toBe((vi.mocked(adminWrite).mock.calls[1]![1] as { operationId: string }).operationId)
})
it('clears management data when live authorization is refused', async () => {
  const w = await view(true); vi.mocked(getAdminUsers).mockRejectedValue(new ApiError('FORBIDDEN', '权限已回收', 403))
  await w.get('.admin-filters').trigger('submit'); await flushPromises(); expect(w.text()).not.toContain('管理 friend'); expect(w.text()).not.toContain('朋友'); expect(w.text()).toContain('权限已回收')
})
it('keeps password and quota inputs intact during periodic metadata refresh', async () => {
  vi.useFakeTimers(); const w = await view(true); const input = w.get('input[type=password]')
  await input.setValue('pending-reset-password'); await vi.advanceTimersByTimeAsync(30_000); await flushPromises()
  expect((w.get('input[type=password]').element as HTMLInputElement).value).toBe('pending-reset-password')
  expect(w.text()).toContain('管理 friend'); expect(adminWrite).not.toHaveBeenCalled()
})
it('discards a delayed selected-user response after filters change', async () => {
  let resolve!: (value: { data: AdminDetail; meta: typeof meta }) => void
  vi.mocked(getAdminDetail).mockReturnValueOnce(new Promise(r => { resolve = r })); const w = await view()
  await w.findAll('button').find(b => b.text() === '管理与审计')!.trigger('click'); await w.get('.admin-filters').trigger('submit'); await flushPromises()
  resolve({ data: user, meta }); await flushPromises(); expect(w.text()).not.toContain('管理 friend')
})
it('prevents resetting administrators in the web UI', async () => {
  vi.mocked(getAdminDetail).mockResolvedValue({ data: { ...user, role: 'ADMIN' }, meta }); const w = await view(true)
  expect(w.text()).toContain('受控维护命令'); expect(w.find('input[type=password]').exists()).toBe(true) // only create-user password
  expect(w.findAll('button').some(b => b.text() === '停用用户')).toBe(false)
  expect(w.findAll('button').some(b => b.text() === '重置密码并撤销旧会话')).toBe(false)
})
