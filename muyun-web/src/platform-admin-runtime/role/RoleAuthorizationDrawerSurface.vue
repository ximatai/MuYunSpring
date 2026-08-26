<script setup lang="ts">
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import RoleAuthorizationView from '../../views/RoleAuthorizationView.vue';

defineOptions({ name: 'RoleAuthorizationDrawerSurface' });
const props = defineProps<{ context: ModulePageDrawerContext }>();
</script>

<template>
  <RoleAuthorizationView
    v-if="props.context.record?.id != null"
    :role-id="String(props.context.record.id)"
    :container="null"
    drawer
    @close="props.context.close"
  />
</template>
