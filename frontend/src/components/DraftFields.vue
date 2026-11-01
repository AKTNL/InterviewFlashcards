<script setup lang="ts">
import type { CheckFlag, Draft } from '../api/imports'
const value = defineModel<Draft>({ required: true })
// Several glyph issues can have identical kind/reason/page. Index keys would reuse
// an unchecked DOM input for the next unresolved flag after removal.
const flagKeys = new WeakMap<CheckFlag, number>()
let nextFlagKey = 0
function flagKey(flag: CheckFlag) {
  if (!flagKeys.has(flag)) flagKeys.set(flag, nextFlagKey++)
  return flagKeys.get(flag)!
}
defineProps<{ prefix: string }>()
</script>
<template>
  <div class="draft-fields">
    <label :for="`${prefix}-question`">问题</label><textarea :id="`${prefix}-question`" v-model="value.question" maxlength="2000" rows="2" required />
    <fieldset v-for="(block, index) in value.contentBlocks" :key="index">
      <legend>内容 {{ index + 1 }} · 物理页 {{ block.page }}</legend>
      <label v-for="(span, line) in block.textSpans" :key="line">文本 {{ line + 1 }}<textarea v-model="span.text" rows="3" /></label>
      <button type="button" class="secondary-button" @click="block.textSpans.push({ text: '', bbox: [] })">添加文本段</button>
      <button v-if="value.contentBlocks.length > 1" type="button" class="secondary-button" @click="value.contentBlocks.splice(index, 1)">移除此内容块</button>
      <p v-if="block.images.length" class="form-note">本段保留 {{ block.images.length }} 处图片来源，请通过全文原图核对。</p>
    </fieldset>
    <fieldset v-for="(source, index) in value.sources" :key="index">
      <legend>来源范围 {{ index + 1 }}（PDF 左上角坐标）</legend>
      <div class="source-coordinates"><label>物理页<input v-model.number="source.page" type="number" min="1" /></label>
        <label v-for="(coordinate, axis) in ['左', '上', '右', '下']" :key="coordinate">{{ coordinate }}<input v-model.number="source.bbox[axis]" type="number" min="0" step="any" /></label>
      </div>
      <p class="form-note">修改物理页后请同步调整对应内容的页码。</p>
      <button v-if="value.sources.length > 1" type="button" class="secondary-button" @click="value.sources.splice(index, 1)">移除此来源范围</button>
    </fieldset>
    <button type="button" class="secondary-button" @click="value.sources.push({ page: value.sources[0]?.page ?? 1, bbox: [...(value.sources[0]?.bbox ?? [0, 0, 100, 100])] })">添加来源范围</button>
    <label v-for="(block, index) in value.contentBlocks" :key="`page-${index}`">内容 {{ index + 1 }} 对应物理页<input v-model.number="block.page" type="number" min="1" /></label>
    <fieldset v-for="(followup, index) in value.followups" :key="`f-${index}`"><legend>追问 {{ index + 1 }}</legend>
      <label>追问标题<input v-model="followup.title" /></label><label>追问全文<textarea v-model="followup.text" rows="4" /></label><label>追问物理页<input v-model.number="followup.page" type="number" min="1" /></label>
    </fieldset>
    <fieldset><legend>待检查标记（取消勾选表示已核对）</legend>
      <label v-for="(flag, index) in value.flags" :key="flagKey(flag)" class="flag-row"><input type="checkbox" checked @change="value.flags.splice(index, 1)" />{{ flag.reason }}</label>
      <button type="button" class="secondary-button" @click="value.flags.push({ kind: 'MANUAL_CHECK', reason: '人工标记：需要进一步核对' })">添加待检查标记</button>
    </fieldset>
  </div>
</template>
