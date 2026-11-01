import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { libraryReviewApi, libraryWrite } from '../api/library'
import { importsApi, type ChapterReview, type Draft } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import ChapterReviewView from './ChapterReview.vue'
vi.mock('../api/library', () => ({ libraryReviewApi: vi.fn(), libraryWrite: vi.fn() }))
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
vi.mock('../api/imports', () => ({ importsApi: { review: vi.fn(), draft: vi.fn() } }))
const draft: Draft = { id: 'd2', sourceId: 'source', chapterId: 'chapter', question: '跨页问题', sourceEntry: 'q2', flags: [{ kind: 'CHECK', reason: '核对原文' }], excluded: false, version: 0,
  sources: [{ page: 1, bbox: [0, 0, 600, 800] }], contentBlocks: [{ type: 'SOURCE_TEXT', page: 1, textSpans: [{ text: '完整答案', bbox: [] }], images: [] }], followups: [] }
const review: ChapterReview = { id: 'chapter', name: 'Java 基础', version: 4, checkStatus: 'IN_PROGRESS', abandoned: false, currentDraftId: 'd2',
  items: [{ ...draft, id: 'd1', question: '第一题' }, draft], cards: [], activeCount: 2, flaggedCount: 1, userCountDelta: -1,
  history: [{ action: 'MERGE', reason: '合并跨页', countDelta: -1, createdAt: '2026-10-31' }] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia())
  vi.mocked(importsApi.review).mockResolvedValue(structuredClone(review))
  vi.mocked(importsApi.draft).mockResolvedValue(structuredClone(draft))
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })
function mountView() {
  const pinia = createPinia(); setActivePinia(pinia)
  const sync = useSyncStore(); sync.state = { dataEpoch: 'epoch', revision: 1, sources: [] }
  const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const wrapper = mount(ChapterReviewView, { props: { id: 'chapter' }, global: { plugins: [pinia], stubs: { DraftPreview: true, DraftSourceComparison: true } } }); wrappers.push(wrapper)
  return { wrapper, sync, enqueue }
}
it('resumes the server check position without resetting it and shows independent count changes', async () => {
  const { wrapper, enqueue } = mountView(); await flushPromises()
  expect(importsApi.draft).toHaveBeenCalledWith('d2'); expect(enqueue).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('用户调整 -1 题'); expect(wrapper.text()).toContain('合并跨页')
  await wrapper.findAll('.draft-link')[0]!.trigger('click'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ kind: 'chapter.review', expectedVersion: 4, action: 'PROGRESS', payload: { draftId: 'd1' } }))
})
it('requires an explicit flagged-import choice after showing the complete confirmation scope', async () => {
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.get('.chapter-confirm > button').trigger('click')
  const confirm = wrapper.get('.confirm-panel .primary-button')
  expect(confirm.attributes('disabled')).toBeDefined(); expect(wrapper.get('.confirm-panel').text()).toContain('其中 1 题仍有标记')
  await wrapper.get('.confirm-panel input').setValue(true); await confirm.trigger('click'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'CONFIRM', payload: { allowFlagged: true } }))
  expect(wrapper.text()).toContain('收到服务端确认后才算保存成功')
  expect(wrapper.text()).not.toContain('本章已进入正式题库')
})
it('keeps unsaved corrections during refresh and queues the full content with a reason', async () => {
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '编辑问题、内容与来源')!.trigger('click')
  await wrapper.get('#edit-question').setValue('人工纠错后的问题')
  window.dispatchEvent(new Event('focus')); await flushPromises()
  expect(importsApi.review).toHaveBeenCalledTimes(1)
  await wrapper.get('.review-editor').trigger('submit'); expect(enqueue).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请填写本次调整原因')
  await wrapper.get('#review-reason').setValue('对照 PDF 修正')
  await wrapper.get('.review-editor').trigger('submit'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'EDIT', payload: expect.objectContaining({ reason: '对照 PDF 修正', value: expect.objectContaining({ question: '人工纠错后的问题', sources: draft.sources, contentBlocks: [expect.objectContaining({ type: 'MANUAL_TEXT' })] }) }) }))
  expect(wrapper.text()).toContain('操作已在本机保留')
  expect(wrapper.text()).not.toContain('服务端已确认保存')
})
it('retains pending edits after remount and prevents another write based on stale versions', async () => {
  const { wrapper, sync, enqueue } = mountView()
  sync.items = [{ operationId: 'op', userId: 'user', dataEpoch: 'epoch', kind: 'chapter.review', chapterId: 'chapter', name: '编辑', action: 'EDIT', payload: { value: { question: '本地修正' } }, status: 'pending', createdAt: 1, attempts: 0, nextAttemptAt: 0 }]
  await flushPromises()
  expect(wrapper.text()).toContain('本章有待同步或待处理操作')
  expect(wrapper.findAll('button').find(b => b.text() === '编辑问题、内容与来源')!.attributes('disabled')).toBeDefined()
  await wrapper.findAll('button').find(b => b.text() === '原文并排对照')!.trigger('click')
  expect(wrapper.findComponent({ name: 'DraftSourceComparison' }).props('unsaved')).toBe(true)
  expect(enqueue).not.toHaveBeenCalled()
})
it('lets imported chapters correct existing cards while hiding structural operations and confirmation', async () => {
  vi.mocked(importsApi.review).mockResolvedValue({ ...structuredClone(review), checkStatus: 'IMPORTED', cards: [{ id: 'stable-card', draftId: 'd2' }] })
  const { wrapper } = mountView(); await flushPromises()
  expect(wrapper.text()).toContain('stable-card'); expect(wrapper.text()).toContain('修正现有正式卡片')
  expect(wrapper.text()).not.toContain('合并选中题'); expect(wrapper.text()).not.toContain('拆分当前题'); expect(wrapper.find('.chapter-confirm').exists()).toBe(false)
})
it('requires a separate abandonment choice and reason without affecting temporary exit', async () => {
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '放弃剩余草稿…')!.trigger('click')
  const confirm = wrapper.findAll('button').find(b => b.text() === '确认放弃（需填写原因）')!
  expect(confirm.attributes('disabled')).toBeDefined()
  await wrapper.get('.chapter-confirm details input').setValue(true)
  await wrapper.get('#review-reason').setValue('当前不需要')
  await confirm.trigger('click'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'ABANDON', payload: { confirmed: true, reason: '当前不需要' } }))
})

it('uses the platform review endpoint with explicit confirmation and never queues a personal mutation', async () => {
  vi.mocked(libraryReviewApi).mockReturnValue({ review: vi.fn().mockResolvedValue(structuredClone(review)), draft: vi.fn().mockResolvedValue(structuredClone(draft)) })
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore()
  const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const w = mount(ChapterReviewView, { props: { id: 'chapter', libraryId: 'platform' }, global: { plugins: [pinia], stubs: { DraftPreview: true, RouterLink: true } } }); wrappers.push(w)
  await flushPromises(); await w.get('.chapter-confirm > button').trigger('click'); await w.get('.confirm-panel input').setValue(true)
  await w.get('.confirm-panel .primary-button').trigger('click'); await flushPromises()
  expect(libraryWrite).toHaveBeenCalledWith('/api/admin/library/platform/chapters/chapter/review', expect.objectContaining({ expectedVersion: 4, action: 'CONFIRM', payload: { allowFlagged: true } }))
  expect(enqueue).not.toHaveBeenCalled(); expect(w.text()).toContain('服务端已确认保存')
})

it('concentrates anomaly reasons by type, searches and navigates only matching active rows', async () => {
  const rows = [{ ...draft, id: 'd1', question: '正常问题', flags: [] }, { ...draft, id: 'd2', question: '跨页问题' },
    { ...draft, id: 'd3', question: '跨页表格', flags: [{ kind: 'TABLE', reason: '表格核对', page: 11 }] }, { ...draft, id: 'excluded', question: '排除问题', excluded: true }]
  vi.mocked(importsApi.review).mockResolvedValue({ ...structuredClone(review), items: rows, activeCount: 3, flaggedCount: 2 })
  vi.mocked(importsApi.draft).mockImplementation(async id => ({ ...structuredClone(draft), ...rows.find(d => d.id === id)! }))
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.get('[aria-label="检查范围"]').setValue('FLAGGED')
  expect(wrapper.findAll('.draft-link').map(b => b.text())).toEqual(['跨页问题', '跨页表格'])
  expect(wrapper.get('.flag-reasons').text()).toContain('核对原文')
  await wrapper.findAll('button').find(b => b.text() === '下一待检查题')!.trigger('click'); await flushPromises()
  expect(importsApi.draft).toHaveBeenLastCalledWith('d3')
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'PROGRESS', expectedVersion: 4, payload: { draftId: 'd3' } }))
  await wrapper.get('[aria-label="检查范围"]').setValue('kind:TABLE')
  expect(wrapper.findAll('.draft-link')).toHaveLength(1)
  expect(wrapper.get('.flag-reasons').text()).toContain('物理页 11')
  await wrapper.get('[aria-label="查找候选题目"]').setValue('无结果')
  expect(wrapper.text()).toContain('没有匹配的题目')
  expect(wrapper.get('.chapter-confirm').text()).toContain('将导入 3 题')
})

it('keeps similarity comparison read-only and clears hidden merge choices when filters change', async () => {
  const rows = [{ ...draft, id: 'd1', question: 'HashMap底层原理和扩容机制' }, { ...draft, question: 'HashMap的底层原理和扩容机制' },
    { ...draft, id: 'excluded', excluded: true, question: 'HashMap的底层原理和扩容机制' }]
  vi.mocked(importsApi.review).mockResolvedValue({ ...structuredClone(review), items: rows })
  const { wrapper, enqueue } = mountView(); await flushPromises()
  expect(wrapper.get('[aria-label="相似题提示"]').text()).toContain('本章相似题提示（1）')
  await wrapper.findAll('button').find(b => b.text() === '只读对比')!.trigger('click'); await flushPromises()
  expect(importsApi.draft).toHaveBeenLastCalledWith('d1')
  expect(enqueue).not.toHaveBeenCalled()
  expect(wrapper.get('.draft-link[aria-pressed="true"]').text()).toBe(rows[1]!.question)
  expect(wrapper.findAll('.draft-list input[type=checkbox]').every(c => !(c.element as HTMLInputElement).checked)).toBe(true)
  await wrapper.get('.draft-list input[type=checkbox]').setValue(true)
  await wrapper.get('[aria-label="检查范围"]').setValue('FLAGGED')
  expect(wrapper.text()).toContain('合并选中题（0）')
  expect(enqueue).not.toHaveBeenCalled()
})

it('keeps unsaved edits and flag removals while using comparison and requires the existing versioned save', async () => {
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '原文并排对照')!.trigger('click')
  expect(wrapper.find('.review-workspace.with-source').exists()).toBe(true)
  await wrapper.findAll('button').find(b => b.text() === '编辑问题、内容与来源')!.trigger('click')
  await wrapper.get('#edit-question').setValue('对照后修正')
  await wrapper.get('.flag-row input').setValue(false)
  expect(wrapper.get('[aria-label="检查范围"]').attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('button').find(b => b.text() === '下一待检查题')!.attributes('disabled')).toBeDefined()
  await wrapper.findAll('button').find(b => b.text() === '收起原文对照')!.trigger('click')
  expect((wrapper.get('#edit-question').element as HTMLTextAreaElement).value).toBe('对照后修正')
  expect(enqueue).not.toHaveBeenCalled()
  await wrapper.get('#review-reason').setValue('已对照来源核对异常')
  await wrapper.get('.review-editor').trigger('submit'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'EDIT', expectedVersion: 4, payload: expect.objectContaining({ value: expect.objectContaining({ flags: [], sources: draft.sources }) }) }))
})

it('ignores an old comparison response after changing the current draft', async () => {
  const rows = [{ ...draft, id: 'd1' }, draft]
  vi.mocked(importsApi.review).mockResolvedValue({ ...structuredClone(review), items: rows })
  const { wrapper } = mountView(); await flushPromises()
  let resolveComparison!: (value: Draft) => void
  vi.mocked(importsApi.draft).mockImplementationOnce(() => new Promise(resolve => { resolveComparison = resolve }))
  await wrapper.findAll('button').find(b => b.text() === '只读对比')!.trigger('click')
  await wrapper.findAll('.draft-link')[0]!.trigger('click'); await flushPromises()
  resolveComparison({ ...draft, id: 'old-comparison' }); await flushPromises()
  expect(wrapper.find('[aria-label="相似题提示"] draft-preview-stub').exists()).toBe(false)
})

it.each([false, true])('keeps remaining anomaly checkboxes checked after resolving the first flag (duplicate reasons: %s)', async duplicate => {
  const first = { kind: 'TABLE', reason: '核对表格', page: 10 }
  const second = duplicate ? { ...first } : { kind: 'GLYPH', reason: '核对字形', page: 10 }
  vi.mocked(importsApi.draft).mockResolvedValue({ ...structuredClone(draft), flags: [first, second] })
  const { wrapper, enqueue } = mountView(); await flushPromises()
  await wrapper.findAll('button').find(b => b.text() === '编辑问题、内容与来源')!.trigger('click')
  await wrapper.findAll('.flag-row input')[0]!.setValue(false)
  expect(wrapper.findAll('.flag-row')).toHaveLength(1)
  expect(wrapper.get('.flag-row').text()).toContain(second.reason)
  expect((wrapper.get('.flag-row input').element as HTMLInputElement).checked).toBe(true)
  expect(enqueue).not.toHaveBeenCalled()
})
