import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { libraryApi, libraryWrite, type LibraryEntry } from '../api/library'
import { useSyncStore } from '../stores/sync'
import AdminLibrary from './AdminLibrary.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('../api/library', () => ({ libraryApi: { list: vi.fn(), preview: vi.fn(), jobs: vi.fn(), chapters: vi.fn() }, libraryWrite: vi.fn() }))
const entry: LibraryEntry = { id: 'lib', name: '平台题库', description: '说明', status: 'DRAFT', version: 3, revision: 12, content_version: 0,
  counts: { chapters: 13, cards: 158, activeTasks: 1, claims: 2 }, manifest: { formatVersion: 1, name: '平台题库', sender: '平台', capturedAt: '', sourceRevision: 0, contentHash: '', counts: {}, chapters: [], sources: [] } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks(); vi.mocked(libraryApi.list).mockResolvedValue([structuredClone(entry)]); vi.mocked(libraryApi.preview).mockResolvedValue(structuredClone(entry)); vi.mocked(libraryApi.jobs).mockResolvedValue([])
})
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); useSyncStore().state = { dataEpoch: 'admin-epoch', revision: 1, sources: [] }
  const w = mount(AdminLibrary, { global: { plugins: [pinia], stubs: { ChapterReview: true } } }); wrappers.push(w); await flushPromises()
  await w.findAll('button').find(b => b.text() === '平台题库')!.trigger('click'); await flushPromises(); return w
}
it('requires a current impact preview and both confirmations before publishing', async () => {
  const w = await view(); await w.findAll('button').find(b => b.text() === '检查并发布…')!.trigger('click'); await flushPromises()
  const panel = w.get('[aria-label="知识库操作确认"]'); expect(panel.text()).toContain('13 章 / 158 题'); expect(panel.text()).toContain('在途解析 1 项'); expect(panel.text()).toContain('已领取 2 人')
  const confirm = panel.get('.primary-button'); expect(confirm.attributes('disabled')).toBeDefined()
  await panel.findAll('input')[0]!.setValue(true); expect(confirm.attributes('disabled')).toBeDefined()
  await panel.findAll('input')[1]!.setValue(true); await confirm.trigger('click'); await flushPromises()
  expect(libraryWrite).toHaveBeenCalledWith('/api/admin/library/lib/transition', { dataEpoch: 'admin-epoch', expectedVersion: 3, expectedRevision: 12, action: 'PUBLISH', confirmed: true, rightsConfirmed: true })
})
it('shows deletion scope and retains the confirmation when the preview becomes stale', async () => {
  vi.mocked(libraryWrite).mockRejectedValueOnce(new Error('数据已变化，请重新读取影响预览并确认'))
  const w = await view(); await w.findAll('button').find(b => b.text() === '删除知识库…')!.trigger('click'); await flushPromises()
  const panel = w.get('[aria-label="知识库操作确认"]'); expect(panel.text()).toContain('已领取副本、笔记和学习进度继续保留')
  await panel.get('input').setValue(true); await panel.get('.primary-button').trigger('click'); await flushPromises()
  expect(w.text()).toContain('数据已变化'); expect(w.find('[aria-label="知识库操作确认"]').exists()).toBe(true)
})
