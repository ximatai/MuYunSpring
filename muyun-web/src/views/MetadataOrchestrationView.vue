<script setup lang="ts">
import { ManagementWorkspace } from '@muyun/platform-components';
import MetadataGovernanceSurface from './MetadataGovernanceSurface.vue';

defineOptions({ name: 'MetadataOrchestrationView' });

defineProps<{ moduleAlias: string; moduleTitle?: string; title?: string }>();
</script>

<template>
  <ManagementWorkspace class="metadata-orchestration-workspace" :explorer-count="0">
    <MetadataGovernanceSurface :module-alias="moduleAlias" :module-title="moduleTitle" :title="title" />
  </ManagementWorkspace>
</template>
