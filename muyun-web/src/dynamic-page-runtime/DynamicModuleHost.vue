<script setup lang="ts">
import type { StandardModulePageDescriptor } from '@muyun/web-contracts';
import ModulePageHost from './ModulePageHost.vue';

/**
 * @deprecated Use ModulePageHost. Remove after persisted `dynamic-module-host`
 * values and extension imports have completed their compatibility window.
 */
defineOptions({ name: 'DynamicModuleHost' });

defineProps<{
  descriptor: StandardModulePageDescriptor;
}>();
</script>

<template>
  <ModulePageHost :descriptor="descriptor" />
</template>
