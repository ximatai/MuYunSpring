<script setup lang="ts">
import { computed } from 'vue';
import { usePageRoute } from '../app/pageRouteContext';
import { useWorkbenchNavigation } from '../platform-workbench/workbenchNavigation';
import ModuleOpenApiView from './ModuleOpenApiView.vue';

const route = usePageRoute();
const navigation = useWorkbenchNavigation();
const moduleAlias = computed(() => String(route.value.params.moduleAlias ?? ''));

function handleTitleResolved(title: string) {
  const instanceKey = route.value.query.InstanceKey;
  if (typeof instanceKey === 'string') navigation?.setTabName(instanceKey, title);
}
</script>

<template>
  <ModuleOpenApiView :module-alias="moduleAlias" @title-resolved="handleTitleResolved" />
</template>
