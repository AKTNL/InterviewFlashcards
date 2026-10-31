import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cardsApi, type CardDetail } from '../api/cards'
import { importsApi } from '../api/imports'
import { useSyncStore } from '../stores/sync'
import CardDetailView from './CardDetailView.vue'
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'card' } }), onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../api/cards', async original => ({ ...await original<typeof import('../api/cards')>(), cardsApi: { detail: vi.fn() } }))
vi.mock('../api/imports', () => ({ importsApi: { draft: vi.fn() } }))
const sample: CardDetail = { id: 'card', draftId: 'draft', chapterId: 'chapter', chapterVersion: 4, question: 'HashMap', version: 3,
  chapterName: 'Java 基础', moduleName: 'Java', deckName: '面试', parentCardId: null, needsCheck: true, displayRevision: 0, applicableVersion: null,
  technicalCorrection: '个人技术纠错', tags: ['集合'], flags: [{ kind: 'CHECK', reason: '核对代码' }], annotation: { notes: '我的笔记', hint: '隐藏的提示', memoryPoints: '隐藏的要点', favorite: false, version: 2 },
  review: { learned: true, dueDate: '2026-11-05', latestRating: 'VAGUE', lapseCount: 2 },
  sources: [{ sourceId: 'source', page: 10, bbox: [1, 2, 300, 400], name: 'source.pdf' }, { sourceId: 'source', page: 11, bbox: [1, 2, 300, 400], name: 'source.pdf' }],
  contentBlocks: [{ type: 'SOURCE_TEXT', page: 10, textSpans: [{ text: '第一段完整答案', bbox: [] }], images: [{ bbox: [] }] }, { type: 'MANUAL_TEXT', page: 11, textSpans: [{ text: '跨页长答案尾部', bbox: [] }], images: [] }],
  originalContent: [], followups: [{ title: '扩容机制', page: 11, text: '完整追问内容' }] }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(cardsApi.detail).mockResolvedValue(structuredClone(sample)); vi.mocked(importsApi.draft).mockResolvedValue({ ...structuredClone(sample), sourceId: 'source', sourceEntry: 'q1', excluded: false }) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore()
  const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const wrapper = mount(CardDetailView, { global: { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' } } } }); wrappers.push(wrapper); await flushPromises()
  return { wrapper, sync, enqueue, button: (text: string) => wrapper.findAll('button').find(b => b.text() === text)! }
}
it('defaults to recall with hidden answer and hints; revealing shows full cross-page answer and source images', async () => {
  const { wrapper, button, enqueue } = await view()
  expect(wrapper.text()).not.toContain('第一段完整答案'); expect(wrapper.text()).not.toContain('隐藏的提示')
  expect(wrapper.text()).toContain('待检查'); expect(wrapper.text()).toContain('复习日期 2026-11-05')
  await button('展开完整答案').trigger('click')
  expect(wrapper.text()).toContain('跨页长答案尾部');expect(wrapper.text()).toContain('PDF 原文提取');expect(wrapper.text()).toContain('人工排版 / 内容修订');expect(wrapper.text()).toContain('个人技术纠错（人工补充）')
  expect(wrapper.get('img').attributes('src')).toBe('/api/sources/source/pages/10/image')
  expect(wrapper.findAll('.card-sources li')).toHaveLength(2)
  expect(wrapper.get('.card-sources a').attributes('data-to')).toContain('1,2,300,400')
  expect(wrapper.get('.followup').attributes('open')).toBeUndefined()
  await button('查看提示与要点').trigger('click');expect(wrapper.text()).toContain('隐藏的提示');expect(wrapper.text()).toContain('独立回忆')
  expect(enqueue).not.toHaveBeenCalled()
})
it('queues favorite using annotation version and preserves all personal text', async () => {
  const { button, enqueue } = await view(); await button('收藏题目').trigger('click'); await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ kind: 'card.update', cardId: 'card', action: 'ANNOTATION', expectedVersion: 2, payload: { notes: '我的笔记', hint: '隐藏的提示', memoryPoints: '隐藏的要点', favorite: true } }))
})
it('keeps an unsaved note across focus refresh and queues it with optimistic locking', async () => {
  const { wrapper, button, enqueue } = await view(); await button('编辑笔记、提示与要点').trigger('click'); await flushPromises()
  await wrapper.get('textarea').setValue('本地新笔记');window.dispatchEvent(new Event('focus'));await flushPromises()
  expect(cardsApi.detail).toHaveBeenCalledTimes(1)
  await wrapper.get('form').trigger('submit');await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'ANNOTATION', expectedVersion: 2, payload: expect.objectContaining({ notes: '本地新笔记' }) }))
  expect(wrapper.text()).toContain('服务端确认结果')
})
it('keeps the editor after storage failure and blocks another change while pending', async () => {
  const { wrapper, button, enqueue, sync } = await view();await button('编辑笔记、提示与要点').trigger('click');await flushPromises()
  await wrapper.get('textarea').setValue('必须保留');enqueue.mockRejectedValueOnce(new Error('本地存储失败'));await wrapper.get('form').trigger('submit');await flushPromises()
  expect(wrapper.get('textarea').element.value).toBe('必须保留');expect(wrapper.text()).toContain('本地存储失败')
  sync.items = [{ kind: 'card.update', cardId: 'card', name: '笔记', status: 'needs-attention', operationId: 'op', userId: 'user', dataEpoch: 'epoch', attempts: 1, createdAt: 1, nextAttemptAt: 0 }];await flushPromises()
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
})
it('separates technical corrections, leaves unknown applicable version null, and uses card version', async () => {
  const { wrapper, button, enqueue } = await view();await button('编辑技术纠错、版本与标签').trigger('click');await flushPromises()
  await wrapper.get('textarea').setValue('基于 JDK 的技术纠错');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ action: 'METADATA', expectedVersion: 3, payload: { technicalCorrection: '基于 JDK 的技术纠错', applicableVersion: null, tags: ['集合'] } }))
})
it('sends full content and check flags through chapter edit, preserving the formal card', async () => {
  const { wrapper, button, enqueue } = await view();await button('修正内容与待检查标记').trigger('click');await flushPromises()
  await wrapper.get('#card-edit-question').setValue('校正标题');await wrapper.get('form').trigger('submit');await flushPromises();expect(enqueue).not.toHaveBeenCalled()
  const reason = wrapper.findAll('input').find(i => i.attributes('required') !== undefined)!;await reason.setValue('核对原文');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(enqueue).toHaveBeenCalledWith(expect.objectContaining({ kind: 'chapter.review', action: 'EDIT', expectedVersion: 4, payload: expect.objectContaining({ draftId: 'draft', reason: '核对原文', value: expect.objectContaining({ question: '校正标题', sources: sample.sources, flags: sample.flags }) }) }))
})
