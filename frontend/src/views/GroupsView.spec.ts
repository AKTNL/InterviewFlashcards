import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { groupsApi, type GroupDetail } from '../api/groups'
import GroupsView from './GroupsView.vue'
vi.mock('../api/groups', () => ({ groupsApi: { list: vi.fn(), detail: vi.fn(), create: vi.fn(), invite: vi.fn(), join: vi.fn(), revoke: vi.fn(), consent: vi.fn(), leave: vi.fn() } }))
const group: GroupDetail = { id: 'group', name: 'Java 小组', owner: true, sharing: false, version: 2, members: [{ nickname: '朋友', self: true, sharing: false }], invites: [] }
const incoming = { id: 'invite', groupId: 'other', name: '朋友的小组', inviter: '同学', expiresAt: '2026-11-02T00:00:00Z' }
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(groupsApi.list).mockResolvedValue({ groups: [{ id: 'group', name: 'Java 小组', owner: true }], incoming: [incoming] })
  vi.mocked(groupsApi.detail).mockResolvedValue(structuredClone(group))
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.useRealTimers() })
async function view(select = false) {
  const w = mount(GroupsView, { attachTo: document.body }); wrappers.push(w); await flushPromises()
  if (select) { await w.get('select').setValue('group'); await flushPromises() }
  return w
}
it('explains nickname scope and gates creating and joining on separate explicit confirmations', async () => {
  const w = await view(); expect(w.text()).toContain('昵称（未设置时显示用户名）'); expect(w.text()).toContain('摘要默认关闭')
  const create = w.findAll('button').find(b => b.text() === '创建并加入')!
  await w.get('[aria-label="创建小组"] input[maxlength="80"]').setValue('新组')
  expect(create.attributes('disabled')).toBeDefined()
  const join = w.findAll('button').find(b => b.text() === '确认加入')!
  expect(join.attributes('disabled')).toBeDefined()
  await w.get('[aria-label="小组邀请"] input[type="checkbox"]').setValue(true)
  await join.trigger('click'); await flushPromises()
  expect(groupsApi.join).toHaveBeenCalledWith('other', 'invite'); expect(groupsApi.consent).not.toHaveBeenCalled()
})
it('reuses the creation UUID after a lost response and does not silently share summaries', async () => {
  const w = await view(); await w.get('[aria-label="创建小组"] input[maxlength="80"]').setValue('新组')
  await w.get('[aria-label="创建小组"] input[type="checkbox"]').setValue(true)
  vi.mocked(groupsApi.create).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValue({ id: 'group' })
  const submit = w.findAll('button').find(b => b.text() === '创建并加入')!
  await submit.trigger('click'); await flushPromises(); await submit.trigger('click'); await flushPromises()
  const calls = vi.mocked(groupsApi.create).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]![0].id).toBe(calls[1]![0].id)
  expect(groupsApi.consent).not.toHaveBeenCalled()
})
it('requires explicit consent and submits the current membership version', async () => {
  const w = await view(true); expect(w.text()).toContain('未分享学习摘要')
  const submit = w.findAll('button').find(b => b.text() === '授权分享摘要')!
  expect(submit.attributes('disabled')).toBeDefined(); await w.get('[aria-label="摘要授权"] input').setValue(true)
  await submit.trigger('click'); await flushPromises(); expect(groupsApi.consent).toHaveBeenCalledWith('group', 2, true)
})
it('allows immediate withdrawal and clears summaries if permission refresh fails', async () => {
  const shared: GroupDetail = { ...group, sharing: true, members: [{ nickname: '朋友', self: true, sharing: true, summary: { learningDate: '2026-11-01', learningZone: 'Asia/Shanghai', studiedToday: true, todayNew: 2, todayReview: 3, weekLearningDays: 4 } }] }
  vi.mocked(groupsApi.detail).mockResolvedValue(shared); const w = await view(true)
  expect(w.text()).toContain('今日新学 2 · 复习 3'); expect(w.text()).toContain('Asia/Shanghai')
  vi.mocked(groupsApi.detail).mockRejectedValue(new Error('已退出或无权限'))
  await w.findAll('button').find(b => b.text() === '停止分享摘要')!.trigger('click'); await flushPromises()
  expect(groupsApi.consent).toHaveBeenCalledWith('group', 2, false)
  expect(w.text()).not.toContain('今日新学 2'); expect(w.text()).not.toContain('朋友（我）')
})
it('rechecks permission on a timer and drops cached summaries on leaving the foreground', async () => {
  vi.useFakeTimers(); const w = await view(true)
  const before = vi.mocked(groupsApi.detail).mock.calls.length
  await vi.advanceTimersByTimeAsync(15_000); await flushPromises(); expect(vi.mocked(groupsApi.detail).mock.calls.length).toBeGreaterThan(before)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  document.dispatchEvent(new Event('visibilitychange')); await flushPromises()
  expect(w.find('[aria-label="摘要授权"]').exists()).toBe(false)
})
it('explains owner dissolution and only leaves after confirmation', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false); const w = await view(true)
  const leave = w.findAll('button').find(b => b.text() === '解散小组并退出')!
  await leave.trigger('click'); expect(groupsApi.leave).not.toHaveBeenCalled()
  confirm.mockReturnValue(true); await leave.trigger('click'); await flushPromises(); expect(groupsApi.leave).toHaveBeenCalledWith('group')
})
it('keeps the invitation input focused and preserves typed content during periodic refresh', async () => {
  vi.useFakeTimers(); const w = await view(true)
  const input = w.get('[aria-label="邀请成员"] input[maxlength="100"]')
  await input.setValue('friend'); (input.element as HTMLInputElement).focus()
  await vi.advanceTimersByTimeAsync(15_000); await flushPromises()
  expect(document.activeElement).toBe(input.element)
  expect((input.element as HTMLInputElement).value).toBe('friend')
})
it('discards a delayed group response after selection changes', async () => {
  let resolve!: (value: GroupDetail) => void
  vi.mocked(groupsApi.detail).mockReturnValueOnce(new Promise(r => { resolve = r }))
  const w = await view(); await w.get('select').setValue('group'); await flushPromises()
  await w.get('select').setValue(''); await flushPromises(); resolve(group); await flushPromises()
  expect(w.find('[aria-label="摘要授权"]').exists()).toBe(false)
})
