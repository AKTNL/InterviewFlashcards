import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { backupsApi, type BackupJob } from '../api/backups'
import BackupsView from './BackupsView.vue'
vi.mock('../api/backups', () => ({ backupsApi: { exports: vi.fn(), restores: vi.fn(), export: vi.fn(), upload: vi.fn(), preview: vi.fn(), confirm: vi.fn() } }))
const task: BackupJob = { id: 'restore', status: 'AWAITING_CONFIRMATION', progress: 50, created_at: '2026-10-31T00:00:00Z', error_code: null,
  validation_result: { manifest: { formatVersion: 1, createdAt: '2026-09-01T00:00:00Z', counts: { card: 10, draft_card: 4, review_event: 20 }, algorithmVersions: ['rules-v1'], files: [] },
    currentCounts: { card: 12 }, expectedEpoch: 'epoch', expectedRevision: 5, scope: '替换当前登录用户的全部学习空间' } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks();vi.mocked(backupsApi.exports).mockResolvedValue([]);vi.mocked(backupsApi.restores).mockResolvedValue([structuredClone(task)]) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount());vi.useRealTimers() })
async function view() { const pinia=createPinia();setActivePinia(pinia);const wrapper=mount(BackupsView,{ global: { plugins: [pinia], stubs: { RouterLink: true } } });wrappers.push(wrapper);await flushPromises();return wrapper }
it('shows replacement counts and requires explicit confirmation before restoring', async () => {
  const w=await view();expect(w.text()).toContain('正式卡片');expect(w.text()).toContain('12');expect(w.text()).toContain('10');expect(w.text()).toContain('逾期题仍然逾期')
  const button=w.findAll('button').find(b=>b.text()==='确认替换并恢复')!;expect(button.attributes('disabled')).toBeDefined()
  await button.trigger('click');expect(backupsApi.confirm).not.toHaveBeenCalled()
  vi.mocked(backupsApi.confirm).mockResolvedValue({ ...task, status: 'RESTORING' })
  await w.get('input[type="checkbox"]').setValue(true);await button.trigger('click');await flushPromises()
  expect(backupsApi.confirm).toHaveBeenCalledWith(task);expect(w.text()).toContain('正在准备资源并替换学习空间')
})
it('retains a stale preview and requires reviewing the refreshed counts again', async () => {
  const w=await view();vi.mocked(backupsApi.confirm).mockRejectedValueOnce(new Error('当前数据已变化，请刷新替换影响预览后重新确认'))
  await w.get('input[type="checkbox"]').setValue(true);await w.findAll('button').find(b=>b.text()==='确认替换并恢复')!.trigger('click');await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('当前数据已变化')
  const next=structuredClone(task);next.validation_result!.expectedRevision=6;next.validation_result!.currentCounts.card=13
  vi.mocked(backupsApi.preview).mockResolvedValue(next);await w.findAll('button').find(b=>b.text()==='刷新影响预览')!.trigger('click');await flushPromises()
  expect(w.text()).toContain('13');expect((w.get('input[type="checkbox"]').element as HTMLInputElement).checked).toBe(false)
})
it('reuses the export operation after a lost response and offers a completed download', async () => {
  const w=await view();vi.mocked(backupsApi.export).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce({ id:'export', status:'READY', progress:100, created_at:task.created_at, error_code:null })
  const button=w.findAll('button').find(b=>b.text()==='导出当前完整数据')!
  await button.trigger('click');await flushPromises();await button.trigger('click');await flushPromises()
  expect(vi.mocked(backupsApi.export).mock.calls[0]![0]).toBe(vi.mocked(backupsApi.export).mock.calls[1]![0]);expect(w.get('a[download]').attributes('href')).toBe('/api/backups/export/download')
})
it('shows rejected archives without exposing replacement controls and polls active jobs', async () => {
  vi.useFakeTimers();vi.mocked(backupsApi.restores).mockResolvedValueOnce([{ ...task, status:'VALIDATING', validation_result:undefined }]).mockResolvedValue([{ ...task,status:'FAILED',error_code:'BACKUP_INVALID',validation_result:undefined }])
  const w=await view();expect(w.find('input[type="checkbox"]').exists()).toBe(false)
  await vi.advanceTimersByTimeAsync(1500);await flushPromises();expect(w.text()).toContain('格式版本不受支持');expect(backupsApi.confirm).not.toHaveBeenCalled()
  expect(w.find('input[type="checkbox"]').exists()).toBe(false)
})
it('waits for the initial task list so a stale list cannot hide a newly submitted export', async () => {
  let finish!: (jobs: BackupJob[]) => void
  vi.mocked(backupsApi.exports).mockReturnValueOnce(new Promise(resolve => { finish=resolve }))
  const w=await view(),button=w.findAll('button').find(b=>b.text()==='导出当前完整数据')!
  expect(button.attributes('disabled')).toBeDefined();await button.trigger('click');expect(backupsApi.export).not.toHaveBeenCalled()
  finish([]);await flushPromises()
  vi.mocked(backupsApi.export).mockResolvedValueOnce({ id:'export',status:'READY',progress:100,created_at:task.created_at,error_code:null })
  await button.trigger('click');await flushPromises();expect(w.get('a[download]').attributes('href')).toBe('/api/backups/export/download')
})
