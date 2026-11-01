import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import PersonalInvites from './PersonalInvites.vue'
import { generateInvite, getInvites, revokeInvite, type InviteOverview } from '../api/auth'
import { ApiError } from '../api/client'
vi.mock('../api/auth', () => ({ generateInvite: vi.fn(), getInvites: vi.fn(), revokeInvite: vi.fn() }))
const meta = { requestId: 'id', serverTime: 'now' }
const invite = { id: 'id', label: '邀请 · id', expiresAt: '2026-11-08T00:00:00Z', remainingUses: 1, usedCount: 0, status: 'AVAILABLE' as const }
const empty: InviteOverview = { allowed: true, totalLimit: 2, successfulCount: 0, remainingQuota: 2, canGenerate: true, reason: null, latest: null }
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.resetAllMocks() })
async function view(data = empty) {
  vi.mocked(getInvites).mockResolvedValue({ data, meta })
  const wrapper = mount(PersonalInvites); wrappers.push(wrapper); await flushPromises(); return wrapper
}
const click = async (w: ReturnType<typeof mount>, name: string) => { await w.findAll('button').find(b => b.text().includes(name))!.trigger('click'); await flushPromises() }
it('exposes permission, quota and reason and cannot generate without authorization', async () => {
  const w = await view({ ...empty, allowed: false, canGenerate: false, reason: '尚无邀请权限，请联系维护者。' })
  expect(w.text()).toContain('未授权'); expect(w.text()).toContain('联系维护者')
  await click(w, '生成邀请码'); expect(generateInvite).not.toHaveBeenCalled()
})
it('shows plaintext only after generation, copies once and clears it on refresh', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText } })
  const w = await view()
  vi.mocked(generateInvite).mockResolvedValue({ data: { invite, token: 'one-time-secret' }, meta })
  vi.mocked(getInvites).mockResolvedValue({ data: { ...empty, canGenerate: false, latest: invite }, meta })
  await click(w, '生成邀请码'); expect(w.get('input').element.value).toBe('one-time-secret')
  await click(w, '复制邀请码'); expect(writeText).toHaveBeenCalledWith('one-time-secret')
  await click(w, '刷新邀请状态'); expect(w.find('input').exists()).toBe(false); expect(w.text()).toContain('无法再次查看原码')
  vi.unstubAllGlobals()
})
it('queries status after a lost generation response and never automatically generates again', async () => {
  const w = await view(); vi.mocked(generateInvite).mockRejectedValue(new ApiError('REQUEST_TIMEOUT', 'timeout', 0))
  vi.mocked(getInvites).mockResolvedValue({ data: { ...empty, latest: invite, canGenerate: false }, meta })
  await click(w, '生成邀请码'); expect(generateInvite).toHaveBeenCalledOnce(); expect(getInvites).toHaveBeenCalledTimes(2)
  expect(w.text()).toContain('不会自动重新发码'); expect(w.find('input').exists()).toBe(false)
})
it('requires explicit revocation and leaves new generation as a separate action', async () => {
  const w = await view({ ...empty, latest: invite, canGenerate: false }); const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  await click(w, '撤销邀请码'); expect(revokeInvite).not.toHaveBeenCalled()
  confirm.mockReturnValue(true);vi.mocked(revokeInvite).mockResolvedValue({ data: true, meta })
  vi.mocked(getInvites).mockResolvedValue({ data: { ...empty, latest: { ...invite, status: 'REVOKED' } }, meta })
  await click(w, '撤销邀请码');expect(revokeInvite).toHaveBeenCalledWith('id');expect(generateInvite).not.toHaveBeenCalled();confirm.mockRestore()
})
it('discards a late generation response after leaving the page', async () => {
  let finish!: (value: Awaited<ReturnType<typeof generateInvite>>) => void
  const w = await view();vi.mocked(generateInvite).mockReturnValue(new Promise(resolve => { finish = resolve }))
  await click(w, '生成邀请码'); w.unmount();finish({ data: { invite, token: 'late-secret' }, meta });await flushPromises()
  expect(getInvites).toHaveBeenCalledOnce()
})
