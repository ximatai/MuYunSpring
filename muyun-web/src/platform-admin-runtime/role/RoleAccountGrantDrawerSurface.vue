<script setup lang="ts">
import { computed } from 'vue';
import type { Role } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import RoleAccountGrantDrawer from '../../views/RoleAccountGrantDrawer.vue';

defineOptions({ name: 'RoleAccountGrantDrawerSurface' });
const props = defineProps<{ context: ModulePageDrawerContext }>();
const module = computed(() => props.context.module as unknown as ModuleContext<Role>);
const role = computed(() => props.context.record as Role | undefined);
</script>

<template>
  <RoleAccountGrantDrawer
    open
    :container="null"
    :context="module"
    :role="role"
    @close="props.context.close"
    @saved="props.context.refreshList"
  />
</template>
