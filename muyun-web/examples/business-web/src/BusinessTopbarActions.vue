<script setup lang="ts">
import {
  UiButton,
  UiDropdown,
  useWorkbenchNavigation,
  type CurrentUser,
  type UiDropdownItem,
} from '@ximatai/muyun-web-app';

defineProps<{ currentUser?: CurrentUser }>();
const navigation = useWorkbenchNavigation();
const items: UiDropdownItem[] = [{ key: 'customers', title: '客户管理', iconName: 'user' }];
function select(key: string) {
  if (key === 'customers') navigation?.openRoute('/business/customers');
}
</script>

<template>
  <UiDropdown v-if="currentUser" v-slot="{ toggle, open }" :items="items" @select="select">
    <UiButton
      class="business-tools-button"
      type="text"
      icon-name="app"
      icon-only
      aria-label="业务快捷入口"
      title="业务快捷入口"
      aria-haspopup="menu"
      :aria-expanded="open"
      @click.stop="toggle"
    />
  </UiDropdown>
</template>

<style scoped>
.business-tools-button {
  width: 36px;
  height: 36px;
  color: var(--muyun-theme-base);
  border-radius: 50%;
}
</style>
