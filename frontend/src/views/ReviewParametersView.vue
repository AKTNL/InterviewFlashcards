<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { reviewParametersApi, type ReviewReport, type SaveReviewParameters } from '../api/reviewParameters'
import { useSyncStore } from '../stores/sync'
import { onSyncRefresh } from '../sync/refresh'
import { ApiError } from '../api/client'
const sync = useSyncStore()
const report = ref<ReviewReport>(), days = ref(90), intervals = ref<number[]>([]), reason = ref('')
const busy = ref(false), loading = ref(false), error = ref(''), message = ref(''), baseline = ref(''), conflict = ref(false)
const pending = ref<SaveReviewParameters>()
const form = () => JSON.stringify([intervals.value, reason.value])
const dirty = computed(() => !!baseline.value && baseline.value !== form())
const valid = computed(() => intervals.value.length === 5 && intervals.value.every((n, i, a) => Number.isInteger(n) && n >= 1 && n <= 365 && (i === 0 || n >= a[i - 1]!))
  && !!reason.value.trim() && reason.value.length <= 500 && JSON.stringify(intervals.value) !== JSON.stringify(report.value?.current.intervals))
let generation = 0
async function load(replace = false) {
  if ((dirty.value || pending.value) && !replace) { message.value = '服务端记录已更新，当前输入已保留；请重新读取后核对。'; return }
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const value = await reviewParametersApi.report(days.value)
    if (current !== generation) return
    if ((dirty.value || pending.value) && !replace) { message.value = '服务端记录已更新，当前输入已保留；请重新读取后核对。'; return }
    report.value = value; intervals.value = [...value.current.intervals]; reason.value = ''; baseline.value = form(); conflict.value = false
  } catch (cause) {
    if (current === generation) { error.value = cause instanceof Error ? cause.message : '读取失败'; report.value = undefined }
  } finally { if (current === generation) loading.value = false }
}
function reload() {
  if ((dirty.value || pending.value) && !window.confirm('重新读取会放弃当前输入；响应丢失的保存可能已经生效，请核对版本历史。继续吗？')) return
  pending.value = undefined; message.value = ''; void load(true)
}
async function save() {
  if (busy.value || loading.value || conflict.value || !sync.state || (!pending.value && (!valid.value || !report.value))) return
  pending.value ??= { operationId: crypto.randomUUID(), dataEpoch: sync.state.dataEpoch,
    expectedVersion: report.value!.current.version, intervals: [...intervals.value], reason: reason.value.trim() }
  busy.value = true; error.value = ''; message.value = ''; generation++
  try {
    const result = await reviewParametersApi.save(pending.value)
    pending.value = undefined; intervals.value = [...result.current.intervals]; reason.value = ''; baseline.value = form()
    message.value = '复习参数已保存，仅后续评分使用新版本；现有到期日期保持不变。'
    await load(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试核对结果'
    if (cause instanceof ApiError && !cause.retryable) { conflict.value = true; pending.value = undefined }
  } finally { busy.value = false }
}
function leave() { return !(dirty.value || pending.value) || window.confirm('有未保存或尚未确认的复习参数，确定离开吗？') }
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { void load(); window.addEventListener('beforeunload', unload) })
onUnmounted(() => { generation++; window.removeEventListener('beforeunload', unload) })
onBeforeRouteLeave(leave)
onSyncRefresh(() => { if (!busy.value) return load() }, ['review_policy', 'review_event', 'review_state', 'card'])
</script>
<template>
  <RouterLink to="/settings">← 返回学习设置</RouterLink>
  <section class="sources-intro"><p class="eyebrow">REVIEW · 复习参数</p><h1>调整间隔，观察后续复习</h1>
    <p>“记住了”逐阶段升级，使用五档间隔；“模糊”退一阶段、“不会”回到起点，均安排明天。同日重练不升级，只会保留或缩短到期日。</p>
    <p>参数只影响保存后的评分，不批量重算已有到期日。撤销恢复原状态，冲突评分更正沿用原评分的参数。</p></section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="message" class="notice" role="status">{{ message }}</p>
  <p v-if="loading" role="status">正在读取复习记录…</p>
  <div class="sync-actions"><button class="secondary-button" :disabled="loading || busy" @click="reload">重新读取参数与记录</button></div>
  <template v-if="report">
    <form class="chapter-review learning-settings" @submit.prevent="save">
      <p>当前版本：<strong class="policy-version">{{ report.current.algorithmVersion }}</strong> · {{ report.current.intervals.join(' / ') }} 天</p>
      <fieldset :disabled="busy || loading || !!pending || conflict"><legend>个人复习间隔</legend>
        <label v-for="(_, i) in intervals" :key="i">阶段 {{ i + 1 }}（天）<input v-model.number="intervals[i]" type="number" min="1" max="365" required /></label>
        <p class="form-note">每档 1～365 天，按顺序不递减。仅调整你自己的安排。</p>
        <label>变更原因<textarea v-model="reason" maxlength="500" required placeholder="例如：最近长间隔复习多次不会，先缩短后两阶段观察" /></label>
      </fieldset>
      <p v-if="dirty" role="status">有未保存的复习参数。</p>
      <p v-if="conflict" role="alert">版本或数据代次已变化，请重新读取、核对后再修改。</p>
      <p v-if="pending" role="status">尚未确认结果。再次保存会核对同一操作；重新读取可检查是否已生效。</p>
      <button class="primary-button" type="submit" :disabled="busy || loading || conflict || !sync.state || (!pending && !valid)">{{ pending ? '重试确认保存' : '保存新版本' }}</button>
    </form>
    <section class="chapter-review"><h2>参数变更记录</h2><p>最近 20 次变更；恢复旧间隔也需填写原因并保存新版本。</p>
      <p v-if="!report.history.length">尚未修改个人参数，当前使用部署默认值。</p>
      <ol><li v-for="p in report.history" :key="p.version"><strong class="policy-version">{{ p.algorithmVersion }}</strong><p>{{ p.intervals.join(' / ') }} 天 · {{ p.createdAt }}<br />{{ p.reason }}</p></li></ol>
    </section>
    <section class="chapter-review"><h2>按版本观察复习效果</h2>
      <label>观察范围<select v-model.number="days" :disabled="busy || loading || dirty || !!pending" @change="load()"><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option><option :value="180">最近 180 天</option></select></label>
      <p>{{ report.from }} ～ {{ report.to }} · {{ report.learningTimezone }}</p>
      <p>把下一次跨日且已到期复习的自评，归到安排该次间隔的版本。首次学习、同日重练、提前复习和重新学习不作为效果样本；撤销或被更正的评分已排除。暂停章节仍包含在内。</p>
      <p>这是个人自评观察，受题目难度、间隔和逾期影响，不能证明某组参数更有效，也不代表客观掌握率。少量样本请继续观察。</p>
      <p v-if="!report.outcomes.length">暂无可评估的到期复习样本。完成后续跨日复习后再来查看。</p>
      <div v-else class="review-report-scroll"><table><thead><tr><th>安排间隔的版本</th><th>间隔参数（天）</th><th>样本 / 题数</th><th>不会 / 模糊 / 记住了</th><th>自评记住比例</th><th>准时 / 逾期</th><th>平均安排 / 逾期天数</th></tr></thead>
        <tbody><tr v-for="(o, i) in report.outcomes" :key="i"><td class="policy-version">{{ o.algorithmVersion }}</td><td>{{ o.parameters.intervals?.join(' / ') ?? '历史参数未记录' }}</td><td>{{ o.observations }} / {{ o.cards }}</td><td>{{ o.forgot }} / {{ o.vague }} / {{ o.remembered }}</td><td>{{ (100 * o.remembered / o.observations).toFixed(1) }}%</td><td>{{ o.onTime }} / {{ o.late }}</td><td>{{ o.averagePlannedDays }} / {{ o.averageLateDays }}</td></tr></tbody></table></div>
      <h3>同期评分活动</h3><p>自评次数包含首次与重练，与上面的效果样本口径不同。</p>
      <p v-if="!report.activity.length">这段时间还没有有效评分。</p>
      <ul><li v-for="a in report.activity" :key="a.algorithmVersion"><span class="policy-version">{{ a.algorithmVersion }}</span>：{{ a.assessments }} 次自评，其中首次 {{ a.firstAssessments }} 次、同日重练 {{ a.replays }} 次</li></ul>
    </section>
  </template>
</template>
<style scoped>
.policy-version { overflow-wrap: anywhere; }
.review-report-scroll { overflow-x: auto; max-width: 100%; }
table { border-collapse: collapse; min-width: 850px; width: 100%; }
th, td { padding: .7rem; text-align: left; border-bottom: 1px solid var(--border, #ddd); }
td:first-child { max-width: 200px; }
textarea { min-height: 90px; width: 100%; box-sizing: border-box; }
</style>
