import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { sharesApi, type ShareInvitation, type SharePreview } from '../api/shares'
import { deckDataApi } from '../api/deckData'
import { useSyncStore } from '../stores/sync'
import SharesView from './SharesView.vue'
vi.mock('../api/shares', () => ({ sharesApi: { list: vi.fn(), preview: vi.fn(), create: vi.fn(), claim: vi.fn(), revoke: vi.fn() } }))
vi.mock('../api/deckData', () => ({ deckDataApi: { decks: vi.fn() } }))
const preview: SharePreview = { formatVersion: 1, name: 'Java', sender: '朋友', capturedAt: '2026-11-01T00:00:00Z', sourceRevision: 4, dataEpoch: 'epoch', revision: 4, contentHash: 'abc', counts: { card: 2, chapter: 1, follow_up: 1, asset: 1 }, chapters: ['Java基础'], sources: [{ name: 'full.pdf', pageCount: 222, includedPages: '1–222', sha256: 'hash' }] }
const invitation: ShareInvitation = { id: 'invite', outgoing: false, recipient: 'friend', expiresAt: '2026-11-02T00:00:00Z', revoked: false, expired: false, claimCount: 0, maxClaims: 1, manifest: preview }
const wrappers: { unmount(): void }[] = []
beforeEach(() => { vi.clearAllMocks(); vi.mocked(deckDataApi.decks).mockResolvedValue([{ id: 'deck', name: 'Java' }]); vi.mocked(sharesApi.list).mockResolvedValue({ outgoing: [], incoming: [{ ...invitation }] }); vi.mocked(sharesApi.preview).mockResolvedValue(preview) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
async function view() {
  const pinia = createPinia(); setActivePinia(pinia); const sync = useSyncStore()
  sync.state = { dataEpoch: 'epoch', revision: 4, sources: [] }; vi.spyOn(sync, 'refresh').mockResolvedValue()
  const w = mount(SharesView, { global: { plugins: [pinia], stubs: { RouterLink: true } } }); wrappers.push(w); await flushPromises(); return { w, sync }
}
it('shows complete PDF pages and requires ownership confirmation before creating an invitation', async () => {
  const { w } = await view()
  await w.get('select').setValue('deck'); await w.get('input[maxlength="100"]').setValue('friend')
  await w.findAll('button').find(b => b.text() === '查看分享清单')!.trigger('click'); await flushPromises()
  expect(w.text()).toContain('完整第 1–222 页'); expect(w.text()).toContain('包括未选择或未确认章节')
  const submit = w.findAll('button').find(b => b.text() === '确认并创建邀请')!
  expect(submit.attributes('disabled')).toBeDefined()
  await w.get('input[type="checkbox"]').setValue(true)
  vi.mocked(sharesApi.create).mockResolvedValue({ share: invitation }); await submit.trigger('click'); await flushPromises()
  expect(sharesApi.create).toHaveBeenCalledWith(expect.objectContaining({ recipient: 'friend', rightsConfirmed: true, dataEpoch: 'epoch', expectedRevision: 4 }))
})
it('retains the operation ID when a creation response is lost', async () => {
  const { w } = await view(); await w.get('select').setValue('deck'); await w.get('input[maxlength="100"]').setValue('friend')
  await w.findAll('button').find(b => b.text() === '查看分享清单')!.trigger('click'); await flushPromises()
  await w.get('input[type="checkbox"]').setValue(true)
  vi.mocked(sharesApi.create).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValue({ share: invitation })
  const submit = w.findAll('button').find(b => b.text() === '确认并创建邀请')!
  await submit.trigger('click'); await flushPromises(); await submit.trigger('click'); await flushPromises()
  const calls = vi.mocked(sharesApi.create).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]![0].operationId).toBe(calls[1]![0].operationId)
})
it('requires recipient confirmation and reuses the claim after a lost response', async () => {
  const { w } = await view(); await w.get('[aria-label="收到的邀请"] select').setValue('invite')
  const submit = w.findAll('button').find(b => b.text() === '确认领取副本')!
  expect(submit.attributes('disabled')).toBeDefined(); expect(w.text()).toContain('完整第 1–222 页')
  await w.get('[aria-label="领取内容预览"] input').setValue(true)
  vi.mocked(sharesApi.claim).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValue({ deckId: 'new-deck' })
  await submit.trigger('click'); await flushPromises(); await submit.trigger('click'); await flushPromises()
  const calls = vi.mocked(sharesApi.claim).mock.calls; expect(calls).toHaveLength(2); expect(calls[0]![1].operationId).toBe(calls[1]![1].operationId)
  expect(w.text()).toContain('全部卡片从未学开始'); expect(w.text()).toContain('请到学习设置选择新章节')
})
it('blocks revoked, expired, used invitations and sharing stale revisions', async () => {
  vi.mocked(sharesApi.list).mockResolvedValue({ outgoing: [], incoming: [{ ...invitation, revoked: true }, { ...invitation, id: 'expired', expired: true }, { ...invitation, id: 'used', claimCount: 1 }] })
  const { w, sync } = await view()
  for (const id of ['invite', 'expired', 'used']) { await w.get('[aria-label="收到的邀请"] select').setValue(id); expect(w.get('[aria-label="领取内容预览"] input').attributes('disabled')).toBeDefined() }
  await w.get('select').setValue('deck'); await w.get('input[maxlength="100"]').setValue('friend'); await w.findAll('button').find(b => b.text() === '查看分享清单')!.trigger('click'); await flushPromises()
  await w.get('[aria-label="分享清单"] input').setValue(true); sync.state = { ...sync.state!, revision: 5 }; await flushPromises()
  expect(w.text()).toContain('数据已变化'); expect((w.get('[aria-label="分享清单"] input').element as HTMLInputElement).checked).toBe(false)
  expect(sharesApi.claim).not.toHaveBeenCalled(); expect(sharesApi.create).not.toHaveBeenCalled()
})
