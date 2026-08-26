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
  <RoleAuthorizationView
    v-if="props.context.record?.id != null"
    :role-id="String(props.context.record.id)"
    :module-context="roleContext"
  />
</template>
