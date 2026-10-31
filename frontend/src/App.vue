<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <a class="skip-link" href="#main">跳转到内容</a>
  <header class="site-header">
    <RouterLink class="brand" to="/" aria-label="拾忆，回到首页">
      <span class="brand-icon" aria-hidden="true">拾</span>
      <span>拾忆 <small>面试卡片</small></span>
    </RouterLink>
    <span class="edition">JAVA · 主动回忆</span>
  </header>
  <main id="main" tabindex="-1"><RouterView /></main>
  <footer class="site-footer">每一次主动回忆，都让知识多留一会儿。</footer>
</template>
