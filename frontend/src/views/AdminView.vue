<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import AdminLibrary from '../components/AdminLibrary.vue'
import { ApiError } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { adminWrite, getAdminUsers, getAdminDetail, getAdminConfiguration, type AdminUser, type AdminDetail, type Configuration } from '../api/admin'
const auth = useAuthStore()
const libraryDirty = ref(false)
function switchTab(value: string) { if (!libraryDirty.value || window.confirm('章节修改尚未保存，确认切换？')) { libraryDirty.value = false; tab.value = value } }
const tab = ref('users'), search = ref(''), state = ref('ALL'), invitation = ref('ALL'), page = ref(0)
const users = ref<AdminUser[]>([]), total = ref(0), detail = ref<AdminDetail | null>(null), config = ref<Configuration | null>(null)
const busy = ref(false), loading = ref(false), error = ref(''), notice = ref('')
const name = ref(''), nickname = ref(''), initial = ref(''), resetPassword = ref(''), inviteEnabled = ref(false), limit = ref(0)
let operationId = crypto.randomUUID(), alive = true, serial = 0, selection = 0
const time = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '尚无记录'
const bytes = (value: number) => `${(value / 1048576).toFixed(1)} MiB`
function failure(cause: unknown) {
  error.value = cause instanceof ApiError ? cause.message : '无法读取或保存，请刷新后核对'
  if (cause instanceof ApiError && ['FORBIDDEN', 'UNAUTHENTICATED', 'ACCOUNT_CHANGED', 'PASSWORD_CHANGE_REQUIRED'].includes(cause.code)) {
    users.value = []; detail.value = null; config.value = null
    void auth.refresh().catch(() => {})
  }
}
async function load() {
  const ticket = ++serial
  loading.value = true; error.value = ''
  try {
    const [list, settings] = await Promise.all([getAdminUsers(new URLSearchParams({ search: search.value, state: state.value, invitation: invitation.value, page: String(page.value), size: '10' })), getAdminConfiguration()])
    if (!alive || ticket !== serial) return
    users.value = list.data.items; total.value = list.data.total; config.value = settings.data
  } catch (cause) { if (alive && ticket === serial) { users.value = []; config.value = null; detail.value = null; failure(cause) } }
  finally { if (alive && ticket === serial) loading.value = false }
}
async function select(id: string) {
  const ticket = ++selection
  detail.value = null; resetPassword.value = ''; error.value = ''
  try {
    const result = await getAdminDetail(id)
    if (!alive || ticket !== selection) return
    detail.value = result.data; inviteEnabled.value = result.data.invitation_enabled; limit.value = result.data.total_limit
  } catch (cause) { if (alive && ticket === selection) failure(cause) }
}
async function create() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const result = await adminWrite<string>('users', { operationId, username: name.value, nickname: nickname.value, password: initial.value })
    if (!alive) return
    initial.value = ''; name.value = ''; nickname.value = ''; operationId = crypto.randomUUID()
    notice.value = '普通用户已创建。请线下交付初始密码，首次登录必须改密。'
    page.value = 0; await load(); await select(result.data)
  } catch (cause) {
    if (alive) { failure(cause); notice.value = '若未收到确认，请先刷新并按用户名查询结果。重试同一次建号需输入原始内容；不会自动重放。' }
  } finally { initial.value = ''; if (alive) busy.value = false }
}
async function update(action: 'status' | 'permission' | 'password-reset' | 'revoke', inviteId?: string) {
  const target = detail.value
  if (!target || busy.value) return
  const prompt = action === 'status' ? `${target.enabled ? '停用' : '启用'} ${target.username}？所有旧会话失效，停用会撤销未消费邀请；资料保留。` : action === 'password-reset' ? `重置 ${target.username} 的密码？所有旧会话失效，用户须改密后继续。` : action === 'revoke' ? '撤销这枚未消费邀请？' : `保存 ${target.username} 的邀请资格和累计总额度 ${limit.value}？成功计数保留，关闭会撤销未消费邀请。`
  if (!window.confirm(prompt)) return
  busy.value = true; error.value = ''; notice.value = ''
  const body = action === 'status' ? { version: target.admin_version, enabled: !target.enabled } : action === 'permission' ? { version: target.admin_version, enabled: inviteEnabled.value, totalLimit: limit.value } : action === 'password-reset' ? { version: target.admin_version, password: resetPassword.value } : { version: target.admin_version }
  const path = action === 'revoke' ? `users/${target.id}/invites/${inviteId}/revoke` : `users/${target.id}/${action}`
  try {
    await adminWrite(path, body)
    if (alive) { notice.value = '操作已确认。'; await load(); await select(target.id) }
  } catch (cause) {
    if (alive) { failure(cause); notice.value = '请重新读取用户详情核对结果，再决定下一步。'; detail.value = null }
  } finally { resetPassword.value = ''; if (alive) busy.value = false }
}
function filter() { page.value = 0; detail.value = null; ++selection; void load() }
function activate() { if (document.visibilityState === 'visible' && !busy.value) { void load() } }
let timer: ReturnType<typeof setInterval>
onMounted(() => { void load(); window.addEventListener('focus', activate); document.addEventListener('visibilitychange', activate); timer = setInterval(activate, 30_000) })
onUnmounted(() => { alive = false; ++serial; ++selection; initial.value = ''; resetPassword.value = ''; clearInterval(timer); window.removeEventListener('focus', activate); document.removeEventListener('visibilitychange', activate) })
</script>

<template>
  <section class="admin-panel" aria-labelledby="admin-title">
    <header><p class="eyebrow">管理空间</p><h1 id="admin-title">管理后台</h1><p class="muted">账号与业务权限管理 · 时间均为 Asia/Shanghai</p></header>
    <nav class="admin-tabs" aria-label="后台功能">
      <button v-for="item in [{ id: 'users', label: '用户管理' }, { id: 'permissions', label: '权限管理' }, { id: 'library', label: '知识库管理' }, { id: 'dashboard', label: 'Dashboard' }]" :key="item.id" :aria-pressed="tab === item.id" class="secondary-button" @click="switchTab(item.id)">{{ item.label }}</button>
    </nav>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <template v-if="tab === 'users' || tab === 'permissions'">
      <section v-if="config && tab === 'permissions'" class="admin-box">
        <h2>全站准入与容量</h2><p>注册：{{ config.registrationEnabled ? '开放' : '关闭' }} · 新增名额：{{ config.allocatedAccounts }} / {{ config.maxAccounts }}</p>
        <p>个人存储上限 {{ bytes(config.capacity.userBytes) }}，来源 {{ config.capacity.sources }} 份，每人同时导入 {{ config.capacity.imports }} 个、备份/恢复 {{ config.capacity.backups }} 个；全站存储预算 {{ bytes(config.capacity.globalBytes) }}，保留空间 {{ bytes(config.capacity.reserveBytes) }}，并行任务上限 {{ config.capacity.globalTasks }}。</p>
        <p class="muted">{{ config.configurationChannel }} 个人授权仍受这些限制约束；管理员角色授予与回收使用受控维护命令。</p>
      </section>
      <form class="admin-filters" @submit.prevent="filter">
        <label>用户名或昵称<input v-model="search" maxlength="100" /></label>
        <label>账号状态<select v-model="state"><option value="ALL">全部</option><option value="ENABLED">启用</option><option value="DISABLED">停用</option></select></label>
        <label>邀请资格<select v-model="invitation"><option value="ALL">全部</option><option value="ENABLED">已授权</option><option value="DISABLED">未授权</option></select></label>
        <button class="primary-button" :disabled="loading || busy">查询 / 刷新</button>
      </form>
      <p v-if="loading" role="status">正在读取用户…</p>
      <p v-else-if="!users.length">暂无符合条件的用户。</p>
      <div class="admin-users">
        <article v-for="user in users" :key="user.id" class="admin-box">
          <h2>{{ user.nickname || user.username }} <small>{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</small></h2>
          <p>{{ user.username }} · {{ user.enabled ? '启用' : '停用' }} · {{ user.must_change_password ? '待首次改密' : '正常密码' }}</p>
          <p class="muted admin-id">{{ user.id }}</p><p>创建：{{ time(user.created_at) }}<br />最近登录：{{ time(user.last_login_at) }}</p>
          <p>存储 {{ bytes(user.storage.usedBytes) }} / {{ bytes(user.storage.maxBytes) }} · {{ user.storage.sources }} 份来源</p>
          <p>邀请{{ user.invitation_enabled ? '已授权' : '未授权' }} · 总额 {{ user.total_limit }} / 成功 {{ user.successful_count }} / 剩余 {{ user.remaining_quota }}</p>
          <button class="secondary-button" :disabled="busy" @click="select(user.id)">管理与审计</button> <button class="secondary-button" @click="tab = 'dashboard'">使用汇总</button>
        </article>
      </div>
      <div class="admin-pagination"><button class="secondary-button" :disabled="!page || loading || busy" @click="page--; load()">上一页</button><span>第 {{ page + 1 }} 页 · 共 {{ total }} 人</span><button class="secondary-button" :disabled="(page + 1) * 10 >= total || loading || busy" @click="page++; load()">下一页</button></div>
      <section v-if="detail" class="admin-box" aria-labelledby="target-title">
        <h2 id="target-title">管理 {{ detail.username }}</h2><p>当前版本 {{ detail.admin_version }} · 角色 {{ detail.role }}</p>
        <template v-if="detail.role === 'USER'">
          <button class="secondary-button" :disabled="busy" @click="update('status')">{{ detail.enabled ? '停用用户' : '启用用户' }}</button>
          <form @submit.prevent="update('permission')"><h3>邀请权限</h3><label><input v-model="inviteEnabled" type="checkbox" :disabled="busy" /> 开放个人邀请</label><label>累计成功总额度<input v-model.number="limit" type="number" min="0" max="10000" required :disabled="busy" /></label><p>已成功 {{ detail.successful_count }} 人。调低、关闭或重新授权均保留此计数。</p><button class="primary-button" :disabled="busy">保存邀请权限</button></form>
          <form @submit.prevent="update('password-reset')"><h3>发起密码重置</h3><label>新的临时密码<input v-model="resetPassword" type="password" autocomplete="new-password" minlength="12" required :disabled="busy" /></label><p class="muted">通过线下方式交付；提交后不再展示，用户首次登录必须修改。</p><button class="secondary-button" :disabled="busy">重置密码并撤销旧会话</button></form>
          <h3>近期个人邀请</h3><p v-if="!detail.invites.length">暂无邀请。</p><p v-for="invite in detail.invites" :key="invite.id" class="admin-id">{{ invite.id }} · {{ invite.status }} · 到期 {{ time(invite.expires_at) }}<button v-if="invite.status === 'AVAILABLE'" class="secondary-button" :disabled="busy" @click="update('revoke', invite.id)">撤销</button></p>
        </template>
        <p v-else class="muted">管理员角色与凭据通过受控维护命令管理，后台仅修改普通用户。</p>
        <h3>最近审计（最多 30 条）</h3><p v-if="!detail.audit.length">暂无管理操作。</p>
        <div v-for="entry in detail.audit" :key="entry.id" class="admin-audit"><p>{{ time(entry.created_at) }} · {{ entry.action }} · {{ entry.result }}</p><p class="muted admin-id">操作者：{{ entry.actor_id || entry.actor_source }}</p><p class="admin-id">{{ entry.before_value }} → {{ entry.after_value }}</p></div>
      </section>
      <section v-if="tab === 'users'" class="admin-box"><h2>新增普通用户</h2><p class="muted">不消耗个人邀请额度，占用全站新增名额。初始密码仅通过线下交付，首次登录须改密。</p>
        <form @submit.prevent="create"><label>用户名<input v-model="name" required maxlength="32" autocomplete="off" :disabled="busy" /></label><label>昵称（可选）<input v-model="nickname" maxlength="60" :disabled="busy" /></label><label>一次性初始密码<input v-model="initial" type="password" autocomplete="new-password" minlength="12" required :disabled="busy" /></label><p class="muted">密码至少 12 个字符，最多 72 个 UTF-8 字节。</p><button class="primary-button" :disabled="busy">创建普通用户</button></form>
      </section>
    </template>
    <AdminLibrary v-else-if="tab === 'library'" @dirty="libraryDirty = $event" />
    <section v-else class="admin-box"><h2>Dashboard 与使用汇总</h2><p>全站与逐用户使用统计将在 P1-14 实现。在线时长尚未采集。</p></section>
  </section>
</template>

<style scoped>
.admin-panel { max-width: 1100px; margin: 0 auto; padding: 1rem; }
.admin-tabs, .admin-filters, .admin-pagination { display: flex; flex-wrap: wrap; align-items: end; gap: .8rem; margin: 1.5rem 0; }
.admin-tabs button[aria-pressed="true"] { border-color: var(--accent, #49715a); font-weight: bold; }
.admin-filters label { display: flex; flex-direction: column; gap: .45rem; flex: 1 1 170px; }
.admin-panel input:not([type="checkbox"]), .admin-panel select { display: block; width: 100%; min-height: 44px; margin-top: .4rem; border: 1px solid #b8c7bc; border-radius: 8px; padding: .6rem .75rem; color: inherit; font: inherit; background: #fff; }
.admin-panel input:focus, .admin-panel select:focus { outline: 2px solid #355c4a; outline-offset: 2px; }
.admin-panel input[type="checkbox"] { width: 18px; height: 18px; vertical-align: middle; }
.admin-filters button { flex: 0 0 auto; }
.admin-users { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 290px), 1fr)); gap: 1rem; }
.admin-box { border: 1px solid var(--border, #ddd); border-radius: 14px; padding: 1.2rem; margin: 1rem 0; min-width: 0; background: var(--surface, #fff); }
.admin-box h2 { font-size: 1.2rem; } .admin-box small { font-size: .8rem; font-weight: normal; }
.admin-id { overflow-wrap: anywhere; font-size: .85rem; } .admin-box form { max-width: 520px; margin: 1.4rem 0; }
.admin-box label { display: block; margin: .6rem 0; } .admin-box input:not([type="checkbox"]) { width: 100%; }
.admin-audit { border-top: 1px solid #ddd; margin-top: 1rem; }
</style>
