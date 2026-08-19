<script setup lang="ts">
import type { DynamicModulePageDescriptor, ModulePageDescriptor } from '@muyun/web-contracts';
import DynamicModuleHost from './DynamicModuleHost.vue';

/**
 * Source-neutral public entry for descriptor-driven standard module pages.
 * The implementation remains shared with the persisted DynamicModuleHost
 * compatibility component, so static and dynamic modules do not fork a UI path.
 */
defineOptions({ name: 'ModulePageHost' });

defineProps<{
  descriptor: ModulePageDescriptor | DynamicModulePageDescriptor;
}>();
</script>

<template>
  <DynamicModuleHost :descriptor="descriptor" />
</template>
