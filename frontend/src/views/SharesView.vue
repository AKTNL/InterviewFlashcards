<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { deckDataApi } from '../api/deckData'
import { sharesApi, type ShareInvitation, type SharePreview, type CreateShare, type ClaimShare, type ShareManifest } from '../api/shares'
import { useSyncStore } from '../stores/sync'
import { onSyncRefresh } from '../sync/refresh'
import { ApiError } from '../api/client'
const sync = useSyncStore()
const decks = ref<{ id: string; name: string; copy_origin?: ShareManifest }[]>([]), selected = ref(''), recipient = ref(''), hours = ref(24)
const preview = ref<SharePreview>(), rights = ref(false), incoming = ref<ShareInvitation[]>([]), outgoing = ref<ShareInvitation[]>([])
const chosen = ref(''), accepted = ref(false), busy = ref(false), loading = ref(false), error = ref(''), message = ref(''), claimedDeck = ref('')
const pending = computed(() => sync.items.some(i => i.status !== 'synced'))
const stale = computed(() => !preview.value || preview.value.dataEpoch !== sync.state?.dataEpoch || preview.value.revision !== sync.state?.revision)
const invitation = computed(() => incoming.value.find(i => i.id === chosen.value))
const available = (i: ShareInvitation) => !i.expired && !i.revoked && i.claimCount < i.maxClaims
const status = (i: ShareInvitation) => i.claimCount ? '已领取' : i.revoked ? '已撤销' : i.expired ? '已过期' : '等待领取'
let generation = 0, createCommand: CreateShare | undefined
const claimCommands = new Map<string, ClaimShare>()
watch([selected, recipient, hours], () => { generation++; preview.value = undefined; rights.value = false; createCommand = undefined })
watch(chosen, () => { accepted.value = false })
watch(stale, value => { if (value) rights.value = false })
function failure(cause: unknown) { error.value = cause instanceof Error ? cause.message : '操作失败，请重试' }
async function load() {
  const current = generation; loading.value = true
  try {
    const [d, s] = await Promise.all([deckDataApi.decks(), sharesApi.list()])
    if (current !== generation) return
    decks.value = d; incoming.value = s.incoming; outgoing.value = s.outgoing
    if (invitation.value && !available(invitation.value)) accepted.value = false
  } catch (cause) { if (current === generation) failure(cause) }
  finally { loading.value = false }
}
async function readPreview() {
  const current = ++generation; preview.value = undefined; rights.value = false; createCommand = undefined; busy.value = true; error.value = ''
  try { await sync.refresh(true); const value = await sharesApi.preview(selected.value); if (current === generation) preview.value = value }
  catch (cause) { if (current === generation) failure(cause) }
  finally { busy.value = false }
}
async function create() {
  if (!preview.value || stale.value || pending.value || !rights.value || busy.value || !recipient.value.trim()) return
  createCommand ??= { operationId: crypto.randomUUID(), dataEpoch: preview.value.dataEpoch, expectedRevision: preview.value.revision, deckId: selected.value, recipient: recipient.value.trim(), expiresInHours: hours.value, rightsConfirmed: true }
  busy.value = true; error.value = ''
  try {
    await sharesApi.create(createCommand); message.value = '邀请已创建，朋友登录后可在本页查看并确认领取。'; rights.value = false; preview.value = undefined; createCommand = undefined
    await sync.refresh(true); await load()
  } catch (cause) { failure(cause) }
  finally { busy.value = false }
}
async function claim() {
  const i = invitation.value
  if (!i || !available(i) || !accepted.value || pending.value || busy.value || !sync.state) return
  let command = claimCommands.get(i.id)
  if (!command || command.dataEpoch !== sync.state.dataEpoch) { command = { operationId: crypto.randomUUID(), dataEpoch: sync.state.dataEpoch, confirmed: true }; claimCommands.set(i.id, command) }
  busy.value = true; error.value = ''
  try {
    const result = await sharesApi.claim(i.id, command); claimedDeck.value = result.deckId; accepted.value = false; message.value = '领取成功：副本已保存，全部卡片从未学开始。请到学习设置选择新章节。'
    await sync.refresh(true); await load()
  } catch (cause) {
    failure(cause)
    if (cause instanceof ApiError && !cause.retryable) { accepted.value = false; await load() }
  } finally { busy.value = false }
}
async function revoke(id: string) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await sharesApi.revoke(id); message.value = '邀请已撤销，已领取的副本仍归接收方所有。'; await load() }
  catch (cause) { failure(cause) }
  finally { busy.value = false }
}
onMounted(load)
onSyncRefresh(() => { if (!busy.value) return load() })
onUnmounted(() => { generation++ })
</script>
<template>
  <section class="sources-intro"><p class="eyebrow">SHARE · 题库副本</p><h1>将整理好的题库交给朋友</h1><p>只复制已确认的内容。双方独立编辑、独立复习；笔记、收藏、历史和学习设置保持私有。</p></section>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p><p v-if="message" role="status" class="notice">{{ message }}</p>
  <p v-if="claimedDeck"><RouterLink :to="{ path: '/cards', query: { deckId: claimedDeck } }">打开领取的题库</RouterLink> · <RouterLink to="/settings">设置学习范围</RouterLink></p>
  <p v-if="pending" class="notice">有尚未同步的操作，请先在“保存与同步”处理，再分享或领取。</p>
  <button class="secondary-button" :disabled="busy || loading" @click="load">刷新邀请与题库</button>
  <section class="chapter-review learning-settings" aria-label="创建分享邀请">
    <h2>分享我的题库</h2><p v-if="!decks.length">还没有题库，请先上传 PDF 并确认章节。</p>
    <fieldset :disabled="busy"><legend>选择题库和接收方</legend>
      <label>题库<select v-model="selected"><option value="">请选择题库</option><option v-for="d in decks" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
      <label>朋友的登录用户名<input v-model="recipient" maxlength="100" autocomplete="off" /></label>
      <label>有效期（小时）<input v-model.number="hours" type="number" min="1" max="168" /></label>
    </fieldset>
    <p>邀请绑定已注册账号，最多领取 1 次，有效期 1～168 小时。接收方在本页登录预览后主动领取。</p>
    <p class="form-note">每个账号最多保留 20 个未领取且未撤销的邀请；过期邀请可撤销以释放存储空间。</p>
    <button class="secondary-button" :disabled="busy || !selected || !recipient.trim() || hours < 1 || hours > 168" @click="readPreview">查看分享清单</button>
    <section v-if="preview" class="confirm-panel" aria-label="分享清单">
      <h3>{{ preview.name }} · {{ preview.counts.card }} 张卡片 / {{ preview.counts.chapter }} 章</h3>
      <p>已确认章节：{{ preview.chapters.join('、') }}。包含答案、追问、标签、来源和待检查标记。未确认草稿不包含在卡片副本中。</p>
      <p class="notice">原 PDF 将整份提供，包括未选择或未确认章节所在的页面。请核对以下每份文件的全部页面。</p>
      <ul><li v-for="s in preview.sources" :key="s.sha256">{{ s.name }}：完整第 {{ s.includedPages }} 页（共 {{ s.pageCount }} 页）</li></ul>
      <p>接收方领取后拥有独立副本。撤销只能阻止后续领取；原题库修改、删除或恢复不会收回已领取副本。</p>
      <p v-if="stale" role="alert">数据已变化，请重新查看分享清单。</p>
      <label><input v-model="rights" type="checkbox" :disabled="stale || busy" />我已核对完整 PDF 的页面范围，确认有权提供其中的 PDF、图片及整理内容给此账号。</label>
      <button class="primary-button" :disabled="!rights || stale || pending || busy" @click="create">确认并创建邀请</button>
    </section>
  </section>
  <section class="chapter-review learning-settings" aria-label="收到的邀请">
    <h2>收到的邀请</h2><p v-if="!incoming.length">暂无邀请。请让朋友填写你的登录用户名。</p>
    <label v-if="incoming.length">选择邀请<select v-model="chosen" :disabled="busy"><option value="">请选择邀请</option><option v-for="i in incoming" :key="i.id" :value="i.id">{{ i.manifest.sender }} · {{ i.manifest.name }} · {{ status(i) }}</option></select></label>
    <section v-if="invitation" class="confirm-panel" aria-label="领取内容预览">
      <h3>{{ invitation.manifest.name }} · 来自 {{ invitation.manifest.sender }}</h3>
      <p>{{ invitation.manifest.counts.card }} 张卡片、{{ invitation.manifest.counts.chapter }} 章、{{ invitation.manifest.counts.follow_up }} 个追问、{{ invitation.manifest.counts.asset }} 张图片。章节：{{ invitation.manifest.chapters.join('、') }}</p>
      <p>包含以下原 PDF 的全部页面（也可能包含未导入章节）：</p>
      <ul><li v-for="s in invitation.manifest.sources" :key="s.sha256">{{ s.name }}：完整第 {{ s.includedPages }} 页</li></ul>
      <p>有效期至 {{ new Date(invitation.expiresAt).toLocaleString() }} · {{ status(invitation) }} · 最多领取 {{ invitation.maxClaims }} 次</p>
      <details><summary>来源与内容版本</summary><p>快照：{{ invitation.manifest.snapshotId }}<br />内容修订：{{ invitation.manifest.sourceRevision }}<br />生成时间：{{ invitation.manifest.capturedAt }}<br /><span class="share-hash">SHA-256：{{ invitation.manifest.contentHash }}</span></p></details>
      <label><input v-model="accepted" type="checkbox" :disabled="!available(invitation) || busy" />我已核对来源范围，确认复制到我的空间，从未学开始；后续自行编辑和复习。</label>
      <button class="primary-button" :disabled="!accepted || !available(invitation) || pending || busy || !sync.state" @click="claim">确认领取副本</button>
    </section>
  </section>
  <section class="chapter-review" aria-label="发出的邀请"><h2>发出的邀请</h2><p v-if="!outgoing.length">暂无发出的邀请。</p>
    <article v-for="i in outgoing" :key="i.id" class="confirm-panel"><h3>{{ i.manifest.name }} → {{ i.recipient }}</h3><p>{{ status(i) }} · {{ i.claimCount }}/{{ i.maxClaims }} 次 · 有效期至 {{ new Date(i.expiresAt).toLocaleString() }}</p><button v-if="!i.revoked" class="secondary-button" :disabled="busy" @click="revoke(i.id)">撤销邀请</button></article>
  </section>
  <section v-if="decks.some(d => d.copy_origin?.snapshotId)" class="chapter-review"><h2>已领取题库的来源</h2><article v-for="d in decks.filter(d => d.copy_origin?.snapshotId)" :key="d.id"><h3>{{ d.name }}</h3><p>来自 {{ d.copy_origin?.sender }} · 内容修订 {{ d.copy_origin?.sourceRevision }} · 快照 {{ d.copy_origin?.snapshotId }}</p><p class="share-hash">{{ d.copy_origin?.contentHash }}</p></article></section>
</template>
