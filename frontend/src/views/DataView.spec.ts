import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { deckDataApi } from '../api/deckData'
import { useSyncStore } from '../stores/sync'
import DataView from './DataView.vue'
vi.mock('../api/deckData', () => ({ deckDataApi: { decks: vi.fn(), preview: vi.fn() } }))
const impact = { deckId: 'deck', name: 'Java', dataEpoch: 'epoch', revision: 2, counts: { cards: 17, drafts: 158, annotations: 1, learned: 1, events: 3, sessions: 2 } }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(deckDataApi.decks).mockResolvedValue([{ id: 'deck', name: 'Java' }]); vi.mocked(deckDataApi.preview).mockResolvedValue(structuredClone(impact)) })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks() })
async function view() {
  const pinia = createPinia();setActivePinia(pinia);const sync = useSyncStore();sync.state = { dataEpoch: 'epoch', revision: 2, sources: [] }
  vi.spyOn(sync, 'refresh').mockResolvedValue(undefined);const enqueue = vi.spyOn(sync, 'enqueue').mockResolvedValue(undefined)
  const wrapper = mount(DataView, { global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } });wrappers.push(wrapper);await flushPromises()
  const button = (text: string) => wrapper.findAll('button').find(b => b.text() === text)!
  await wrapper.findAll('select')[0]!.setValue('deck');await button('读取影响预览').trigger('click');await flushPromises()
  return { wrapper, sync, enqueue, button }
}
it('previews reset impact and requires explicit consent before persisting the exact revision', async () => {
  const { wrapper, enqueue, button } = await view()
  expect(wrapper.text()).toContain('17 张正式卡片');expect(wrapper.text()).toContain('回到未学');expect(button('确认重置进度').attributes('disabled')).toBeDefined()
  await wrapper.get('input[type=checkbox]').setValue(true);await button('确认重置进度').trigger('click')
  expect(enqueue).toHaveBeenCalledWith({ kind: 'deck.data', name: '重置进度：Java', action: 'RESET', expectedVersion: 2, payload: { deckId: 'deck' } })
  expect(wrapper.text()).toContain('等待服务端确认');expect(wrapper.text()).not.toContain('学习进度已重置')
})
it('clears consent on remote changes and blocks submission of stale previews', async () => {
  const { wrapper, sync, enqueue, button } = await view();await wrapper.get('input[type=checkbox]').setValue(true)
  sync.state = { dataEpoch: 'epoch', revision: 3, sources: [] };await flushPromises()
  expect((wrapper.get('input').element as HTMLInputElement).checked).toBe(false);expect(button('确认重置进度').attributes('disabled')).toBeDefined();expect(enqueue).not.toHaveBeenCalled()
})
it('requires a new preview and new consent when switching from history reset to content deletion', async () => {
  const { wrapper, button } = await view();await wrapper.get('input[type=checkbox]').setValue(true)
  await wrapper.findAll('select')[1]!.setValue('DELETE');expect(wrapper.find('input[type=checkbox]').exists()).toBe(false)
  await button('读取影响预览').trigger('click');await flushPromises();expect(wrapper.text()).toContain('全部正式卡片、草稿、检查记录、笔记');expect(wrapper.text()).toContain('原 PDF 和图片资源保留');expect(button('确认删除题库').attributes('disabled')).toBeDefined()
})
it('retains preview on local storage failure and offers retry without claiming success', async () => {
  const { wrapper, enqueue, button } = await view();enqueue.mockRejectedValueOnce(new Error('无法保留操作'))
  await wrapper.get('input[type=checkbox]').setValue(true);await button('确认重置进度').trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('无法保留操作');expect(wrapper.text()).not.toContain('学习进度已重置');expect(button('确认重置进度').attributes('disabled')).toBeUndefined()
})
it('blocks new destructive operations while any local write is unresolved', async () => {
  const { wrapper, sync, button } = await view();sync.items = [{ kind: 'review.write', name: '评分', operationId: 'op', userId: 'u', dataEpoch: 'epoch', createdAt: 1, status: 'pending', attempts: 0, nextAttemptAt: 0 }];await flushPromises()
  expect(wrapper.get('input[type=checkbox]').attributes('disabled')).toBeDefined();expect(button('确认重置进度').attributes('disabled')).toBeDefined()
})
