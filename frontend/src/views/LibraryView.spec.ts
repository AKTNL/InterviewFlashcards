import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { libraryApi, libraryWrite, type LibraryEntry } from '../api/library'
import { useAuthStore } from '../stores/auth'
import { useSyncStore } from '../stores/sync'
import LibraryView from './LibraryView.vue'
vi.mock('../api/library', () => ({ libraryApi: { catalog: vi.fn(), claims: vi.fn() }, libraryWrite: vi.fn() }))
const entry: LibraryEntry = { id: 'library', name: '平台 Java', description: '章节说明', status: 'PUBLISHED', version: 2, revision: 4, content_version: 1,
  counts: { chapters: 1, cards: 2, activeTasks: 0, claims: 0 },
  manifest: { name: '平台 Java', sender: '平台知识库', formatVersion: 1, capturedAt: '', sourceRevision: 4, contentHash: 'hash', counts: { card: 2, chapter: 1 }, chapters: ['第一章'], sources: [{ name: 'full.pdf', pageCount: 222, includedPages: '1–222', sha256: 'hash' }] } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(libraryApi.catalog).mockResolvedValue([structuredClone(entry)]); vi.mocked(libraryApi.claims).mockResolvedValue([]) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view(loggedIn = true) {
  const pinia = createPinia(); setActivePinia(pinia)
  if (loggedIn) useAuthStore().user = { id: 'user', username: 'user', dataEpoch: 'epoch', revision: 4, learningTimezone: 'Asia/Shanghai' }
  const sync = useSyncStore(); sync.state = { dataEpoch: 'epoch', revision: 4, sources: [] }; const refresh = vi.spyOn(sync, 'refresh').mockResolvedValue()
  const w = mount(LibraryView, { global: { plugins: [pinia], stubs: { RouterLink: { template: "<a><slot /></a>" } } } }); wrappers.push(w); await flushPromises(); return { w, refresh }
}
it('allows anonymous metadata preview and asks for login before claiming', async () => {
  const { w } = await view(false)
  expect(w.text()).toContain('完整 PDF 第 1–222 页'); expect(w.text()).toContain('登录后领取'); expect(libraryApi.claims).not.toHaveBeenCalled()
  expect(w.findAll('button').some(b => b.text() === '领取独立副本…')).toBe(false)
})
it('requires explicit confirmation, submits the shown content version and refreshes the personal space', async () => {
  const { w, refresh } = await view()
  await w.findAll('button').find(b => b.text() === '领取独立副本…')!.trigger('click')
  const confirm = w.get('[aria-label="领取知识库确认"] .primary-button'); expect(confirm.attributes('disabled')).toBeDefined()
  await w.get('input[type="checkbox"]').setValue(true); await confirm.trigger('click'); await flushPromises()
  expect(libraryWrite).toHaveBeenCalledWith('/api/library/library/claim', { dataEpoch: 'epoch', contentVersion: 1, confirmed: true })
  expect(refresh).toHaveBeenCalledOnce(); expect(w.text()).toContain('请到学习设置选择新增章节')
})
it('keeps the confirmation available after network failure without reporting success', async () => {
  vi.mocked(libraryWrite).mockRejectedValueOnce(new Error('网络中断'))
  const { w, refresh } = await view(); await w.findAll('button').find(b => b.text() === '领取独立副本…')!.trigger('click')
  await w.get('input[type="checkbox"]').setValue(true); await w.get('[aria-label="领取知识库确认"] .primary-button').trigger('click'); await flushPromises()
  expect(w.text()).toContain('网络中断'); expect(w.find('[aria-label="领取知识库确认"]').exists()).toBe(true); expect(refresh).not.toHaveBeenCalled()
})
it('shows already claimed entries without a second claim action', async () => {
  vi.mocked(libraryApi.claims).mockResolvedValue([{ library_id: 'library', deck_id: 'copy', content_version: 1 }])
  const { w } = await view(); expect(w.text()).toContain('已领取，个人副本保持独立')
  expect(w.findAll('button').some(b => b.text() === '领取独立副本…')).toBe(false)
})
