<script setup lang="ts">
import { computed, ref } from 'vue';
import { UiButton, UiDropdown, UiModal, type UiDropdownItem } from '@muyun/vue-ui-antdv';
import type { CurrentUser } from '@muyun/web-contracts';

withDefaults(defineProps<{ currentUser?: CurrentUser }>(), { currentUser: undefined });
const panelOpen = ref(false);
const alternateStyle = ref(false);
const items = computed<UiDropdownItem[]>(() => [
  { key: 'panel', title: '打开示例面板', iconName: 'app' },
  { key: 'style', title: alternateStyle.value ? '切换为圆角图标' : '切换为圆形图标', iconName: 'skin' },
]);
function select(key: string) {
  if (key === 'panel') panelOpen.value = true;
  if (key === 'style') alternateStyle.value = !alternateStyle.value;
}
</script>

<template>
  <UiDropdown v-slot="{ toggle, open }" :items="items" @select="select">
    <UiButton
      class="development-business-entry"
      :class="{ 'development-business-entry--round': alternateStyle }"
      type="text"
      icon-name="app"
      icon-only
      aria-label="业务扩展示例"
      title="业务扩展示例"
      aria-haspopup="menu"
      :aria-expanded="open"
      @click.stop="toggle"
    />
  </UiDropdown>
  <UiModal :open="panelOpen" title="业务扩展示例" @cancel="panelOpen = false">
    <p>你好，{{ currentUser?.username ?? '访客' }}。</p>
    <p>这里可以放业务应用自己的快捷操作、消息或服务入口。</p>
    <p>试试菜单中的外观切换，图标样式和菜单交互均由示例组件控制。</p>
    <template #operation>
      <UiButton type="primary" @click="panelOpen = false">知道了</UiButton>
    </template>
  </UiModal>
</template>

<style scoped>
.development-business-entry {
  width: 36px;
  height: 36px;
  margin-inline: 4px;
  border: 1px solid #fed7aa;
  border-radius: 9px;
  color: #c2410c;
  background: #fff7ed;
}
.development-business-entry:hover,
.development-business-entry[aria-expanded='true'] {
  color: #9a3412;
  border-color: #fb923c;
  background: #ffedd5;
}
.development-business-entry--round {
  border-radius: 50%;
  color: #6d28d9;
  border-color: #ddd6fe;
  background: #f5f3ff;
}
</style>
