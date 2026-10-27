<script setup lang="ts">
import type { PageDescriptor } from '@muyun/web-contracts';
import WorkbenchOutlet from '../platform-workbench/WorkbenchOutlet.vue';
import { isPlatformAdminRoutePage } from '../platform-admin-runtime/platformAdminRoutes';
import PlatformAdminRouteOutlet from '../platform-admin-runtime/PlatformAdminOutlet.vue';

defineOptions({ name: 'PlatformAdminOutlet' });

defineProps<{
  descriptor?: PageDescriptor;
}>();
</script>

<template>
  <WorkbenchOutlet :descriptor="descriptor">
    <template v-if="isPlatformAdminRoutePage(descriptor)" #default>
      <PlatformAdminRouteOutlet :descriptor="descriptor" />
    </template>
  </WorkbenchOutlet>
</template>
