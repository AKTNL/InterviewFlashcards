import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { importsApi, type ChapterReview, type Draft } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import ChapterReviewView from './ChapterReview.vue'
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
  const wrapper = mount(ChapterReviewView, { props: { id: 'chapter' }, global: { plugins: [pinia], stubs: { DraftPreview: true } } }); wrappers.push(wrapper)
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
