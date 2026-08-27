<script setup lang="ts">
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import type { Role } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import RoleAuthorizationView from '../../views/RoleAuthorizationView.vue';

defineOptions({ name: 'RoleAuthorizationDrawerSurface' });
const props = defineProps<{ context: ModulePageDrawerContext }>();
const roleContext = props.context.module as ModuleContext<Role>;
</script>

<template>
  <section class="role-authorization-drawer-surface">
    <RoleAuthorizationView
      v-if="props.context.record?.id != null"
      :role-id="String(props.context.record.id)"
      :module-context="roleContext"
      :drawer-context="props.context"
    />
  </section>
</template>

<style scoped>
.role-authorization-drawer-surface {
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.role-authorization-drawer-surface :deep(.role-authorization-page) {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
