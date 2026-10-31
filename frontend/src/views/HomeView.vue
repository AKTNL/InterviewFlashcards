<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useServiceStore } from '../stores/service'
import { useAuthStore } from '../stores/auth'
import TodayLearning from '../components/TodayLearning.vue'

const service = useServiceStore()
const auth = useAuthStore()
onMounted(() => { void service.check() })
</script>

<template>
  <TodayLearning v-if="auth.user" />
  <section v-else class="intro">
    <p class="eyebrow">INTERVIEW FLASHCARDS</p>
    <h1>把理解，<br />变成随时想得起。</h1>
    <p class="lead">从一个问题开始，先回忆，再阅读完整答案。<br class="desktop-break" />让每次复习都有迹可循。</p>
  </section>

  <section v-if="!auth.user" class="workspace" aria-labelledby="workspace-title">
    <div class="workspace-copy">
      <span class="section-number">01 / 学习空间</span>
      <h2 id="workspace-title">你的卡片，从这里开始</h2>
      <p>登录你的个人空间，上传 PDF，逐章检查并加入题库，按自己的节奏整理。</p>
      <RouterLink class="primary-button" to="/sources">进入我的资料 <span aria-hidden="true">→</span></RouterLink>
      <div class="steps" aria-label="学习方式">
        <span>看问题</span><span aria-hidden="true">→</span><span>主动回忆</span><span aria-hidden="true">→</span><span>核对答案</span>
      </div>
    </div>
    <div class="sample-card" aria-hidden="true">
      <div class="sample-label">JAVA / 一次回忆</div>
      <p>先想一想，<br />你会如何解释？</p>
      <div class="sample-rule"></div>
      <span>给思考留一点时间</span>
    </div>
  </section>

  <section class="service-panel" aria-labelledby="service-title">
    <div>
      <h2 id="service-title">服务连接</h2>
      <div class="service-status" role="status" aria-live="polite">
        <span class="status-dot" :class="{ connected: service.health, failed: service.error }" aria-hidden="true"></span>
        <span v-if="service.loading">正在连接…</span>
        <span v-else-if="service.error">{{ service.error.message }}</span>
        <span v-else-if="service.health">服务与数据库连接正常</span>
        <span v-else>尚未检查</span>
      </div>
      <p v-if="service.health" class="service-detail">学习日期 {{ service.health.learningDate }} · {{ service.health.learningTimezone }}</p>
      <p v-if="service.error?.requestId" class="request-id">请求编号 {{ service.error.requestId }}</p>
    </div>
    <button class="secondary-button" :disabled="service.loading" @click="service.check()">
      {{ service.loading ? '检查中…' : service.error ? '重新连接' : '检查连接' }}
    </button>
  </section>
</template>
