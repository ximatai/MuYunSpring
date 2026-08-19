<script setup lang="ts">
import { UiActionButton } from '@muyun/vue-ui-antdv';
import type { DrawerTitleAction } from './drawerTitleActions';

defineOptions({ name: 'DrawerTitleActions' });

defineProps<{
  actions: DrawerTitleAction[];
}>();
</script>

<template>
  <UiActionButton
    v-for="action in actions"
    :key="action.key"
    :emphasis="action.emphasis ?? 'secondary'"
    :intent="action.intent ?? 'normal'"
    :title="action.title"
    :disabled="action.disabled"
    :loading="action.loading"
    @click="void action.run()"
  >
    {{ action.label }}
  </UiActionButton>
</template>
