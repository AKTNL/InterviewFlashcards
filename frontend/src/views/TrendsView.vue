<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { learningApi, type LearningTrends } from '../api/learning'
import { ratings } from '../api/cards'
import { onSyncRefresh } from '../sync/refresh'

const data = ref<LearningTrends>(), error = ref(''), loading = ref(false), weeks = ref(8), offset = ref(0)
const totals = computed(() => data.value?.weeks.reduce((sum, w) => ({ newCount: sum.newCount + w.newCount, reviewCount: sum.reviewCount + w.reviewCount, learningDays: sum.learningDays + w.learningDays, assessments: sum.assessments + w.assessments }), { newCount: 0, reviewCount: 0, learningDays: 0, assessments: 0 }))
const peak = computed(() => Math.max(1, ...data.value?.weeks.map(w => w.newCount + w.reviewCount) ?? []))
const updatedAt = computed(() => data.value ? new Intl.DateTimeFormat('zh-CN', { timeZone: data.value.learningTimezone, dateStyle: 'short', timeStyle: 'medium' }).format(new Date(data.value.generatedAt)) : '')
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true; error.value = ''; data.value = undefined
  try { const result = await learningApi.trends(weeks.value, offset.value); if (current === generation) data.value = result }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '读取学习趋势失败' }
  finally { if (current === generation) loading.value = false }
}
function changeRange() { offset.value = 0; void load() }
function page(direction: number) { offset.value = Math.max(0, offset.value + direction * 20); void load() }
function activate() { if (document.visibilityState === 'visible') void load() }
function delta(value: number) { return value > 0 ? `+${value}` : String(value) }
onMounted(() => { void load(); window.addEventListener('focus', activate); document.addEventListener('visibilitychange', activate) })
onUnmounted(() => { generation++; window.removeEventListener('focus', activate); document.removeEventListener('visibilitychange', activate) })
onSyncRefresh(load)
</script>

<template>
  <section class="trends-page" aria-labelledby="trends-title">
    <div class="list-heading"><div><p class="eyebrow">LEARNING RECORDS</p><h1 id="trends-title">学习趋势</h1></div><RouterLink to="/">返回今日学习</RouterLink></div>
    <div class="sync-actions"><label>查看范围 <select v-model.number="weeks" @change="changeRange"><option :value="4">近 4 周</option><option :value="8">近 8 周</option><option :value="12">近 12 周</option><option :value="52">近 52 周</option></select></label><button class="secondary-button" :disabled="loading" @click="load">刷新趋势</button></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}，请刷新重试。</p><p v-if="loading" role="status">正在读取学习记录…</p>
    <template v-if="data && totals">
      <p class="muted">{{ data.from }} ～ {{ data.to }} · {{ data.learningTimezone }} · 学习日 {{ data.learningDate }}<br />更新于 {{ updatedAt }}（{{ data.learningTimezone }}）</p>
      <p class="notice">只统计服务端已确认的有效评分。撤销和被更正的原评分不计入；离线待同步操作确认后才显示。仅阅读不计为学习。</p>
      <div class="today-metrics"><div><strong>{{ totals.newCount }}</strong><span>区间新学题数</span></div><div><strong>{{ totals.reviewCount }}</strong><span>区间复习题次</span></div><div><strong>{{ totals.learningDays }}</strong><span>学习天数</span></div><div><strong>{{ totals.assessments }}</strong><span>有效自评次数（含重练）</span></div></div>
      <section aria-labelledby="weekly-title"><h2 id="weekly-title">按周学习记录</h2><p>每周一开始，本周截至学习日。新学按首次有效评分去重；复习按“卡片 × 学习日”去重，同一题跨日复习累计题次。同日首次学习后的重练不算复习。</p>
        <p v-if="!totals.assessments" class="notice">此区间暂无有效学习记录，可从今日学习开始。</p>
        <div class="trend-table" tabindex="0" aria-label="按周记录，可横向滚动"><table><thead><tr><th>学习周</th><th>学习量</th><th>学习天数</th><th>新学题数</th><th>复习题次</th><th>自评次数：不会 / 模糊 / 记住了</th></tr></thead><tbody><tr v-for="w in data.weeks" :key="w.weekStart"><th scope="row">{{ w.weekStart }}<br />～ {{ w.weekEnd }}</th><td><div class="volume-bar" :aria-label="`新学 ${w.newCount}，复习 ${w.reviewCount}`"><span class="new-bar" :style="{ width: `${w.newCount / peak * 100}%` }"></span><span class="review-bar" :style="{ width: `${w.reviewCount / peak * 100}%` }"></span></div></td><td>{{ w.learningDays }}</td><td>{{ w.newCount }}</td><td>{{ w.reviewCount }}</td><td>{{ w.forgot }} / {{ w.vague }} / {{ w.remembered }}</td></tr></tbody></table></div>
        <p class="form-note">图例：深绿为新学，浅绿为复习。自评次数包含同日重练，反映自评结果，不代表新增已学或客观掌握率。</p>
      </section>
      <section aria-labelledby="chapter-title"><h2 id="chapter-title">章节变化</h2><p>比较区间开始前与当前的已学、薄弱题数。薄弱指最近有效自评为“不会”或“模糊”。暂停章节也保留记录。</p>
        <p v-if="!data.chapters.length">还没有已确认章节，请先到我的资料完成导入检查。</p>
        <ul v-else class="chapter-trends"><li v-for="ch in data.chapters" :key="ch.id"><h3>{{ ch.deckName }} / {{ ch.moduleName }} / {{ ch.name }}</h3><p>{{ ch.selected ? '学习中' : '已暂停' }} · 共 {{ ch.total }} 题</p><p>已学 {{ ch.learnedBefore }} → {{ ch.learned }}（{{ delta(ch.learned - ch.learnedBefore) }}） · 薄弱 {{ ch.weakBefore }} → {{ ch.weak }}（{{ delta(ch.weak - ch.weakBefore) }}）</p><p>区间新学 {{ ch.newCount }} 题 · 复习 {{ ch.reviewCount }} 题次 · 自评 {{ ch.assessments }} 次</p><p>当前最近自评题数：不会 {{ ch.forgot }} / 模糊 {{ ch.vague }} / 记住了 {{ ch.remembered }}</p></li></ul>
        <p class="form-note">以当前保留的卡片和有效历史重算；删除、重置、恢复备份会改变结果。章节名称与题数为当前内容，不表示历史题库快照。</p>
      </section>
      <section aria-labelledby="forgotten-title"><h2 id="forgotten-title">遗忘次数排序</h2><p>全部历史中遗忘次数大于 0 的题，按次数从多到少，再按到期日和卡片编号排序。次数沿用复习规则：跨日“不会”增加一次，同日重练不增加；包含已暂停章节，与上方周数范围无关。</p>
        <p v-if="!data.forgotten.total">暂无遗忘次数大于 0 的题。</p><p v-else>共 {{ data.forgotten.total }} 题 · 当前第 {{ Math.floor(data.forgotten.offset / data.forgotten.limit) + 1 }} 页</p>
        <ol class="forgotten-list" :start="data.forgotten.offset + 1"><li v-for="card in data.forgotten.items" :key="card.id"><RouterLink :to="`/cards/${card.id}`">{{ card.question }}</RouterLink><p>{{ card.deckName }} / {{ card.moduleName }} / {{ card.chapterName }}</p><p>遗忘 {{ card.lapseCount }} 次 · 最近自评 {{ ratings[card.latestRating] }} · 到期 {{ card.dueDate }}</p></li></ol>
        <div v-if="data.forgotten.total" class="sync-actions"><button class="secondary-button" :disabled="loading || !offset" @click="page(-1)">上一页</button><button class="secondary-button" :disabled="loading || offset + data.forgotten.limit >= data.forgotten.total" @click="page(1)">下一页</button></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.trends-page { --border: #dcded7; }
.trends-page { max-width: 1100px; margin: 0 auto; } section section { margin-top: 2.5rem; } h1 { font-size: clamp(1.8rem, 5vw, 2.8rem); } p { line-height: 1.7; overflow-wrap: anywhere; } select { padding: .5rem; font: inherit; } .trend-table { overflow: auto; margin-top: 1rem; max-height: 32rem; } table { border-collapse: collapse; width: 100%; min-width: 760px; } th, td { padding: .8rem; text-align: left; border-bottom: 1px solid var(--border); white-space: nowrap; } .volume-bar { display: flex; width: 120px; height: 14px; background: #edf0ed; border-radius: 3px; overflow: hidden; } .new-bar { background: #315b43; } .review-bar { background: #a6c1ae; } .chapter-trends { list-style: none; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 1rem; } .chapter-trends li, .forgotten-list li { padding: 1rem; border: 1px solid var(--border); border-radius: 8px; overflow-wrap: anywhere; } .chapter-trends h3 { margin-top: 0; } .forgotten-list { padding-left: 1.8rem; } .forgotten-list li { margin-bottom: .7rem; } .forgotten-list p { margin: .4rem 0; }
</style>
