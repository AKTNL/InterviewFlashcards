<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="intro">
    <p class="eyebrow">404 / 页面未找到</p>
    <h1>这页暂时找不到。</h1>
    <p class="lead">可以回到首页，继续查看学习空间。</p>
    <RouterLink class="secondary-button" to="/">回到首页</RouterLink>
  </section>
</template>
