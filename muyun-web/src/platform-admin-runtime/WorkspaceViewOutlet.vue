<script setup lang="ts">
import type { BusinessRoutePageDescriptor } from '@muyun/web-contracts';
import { WorkspaceViewOutlet as WorkbenchWorkspaceViewOutlet } from '@muyun/platform-workbench';

defineOptions({ name: 'PlatformAdminWorkspaceViewOutlet' });
defineProps<{ descriptor: BusinessRoutePageDescriptor }>();
</script>

<template>
  <WorkbenchWorkspaceViewOutlet :descriptor="descriptor" />
</template>
