<script setup lang="ts">
import OpenApiCatalogView from './OpenApiCatalogView.vue';
import { createModuleOpenApiPageDescriptor } from '../platform-admin-runtime/moduleOpenApi';
import { useWorkbenchNavigation } from '../platform-workbench/workbenchNavigation';

const navigation = useWorkbenchNavigation();

function openModuleOpenApi(moduleAlias: string, moduleTitle: string) {
  navigation?.openPage(createModuleOpenApiPageDescriptor(moduleAlias, moduleTitle));
}

function returnToWorkbench() {
  navigation?.closeCurrentTab('/');
}
</script>

<template>
  <OpenApiCatalogView @open="openModuleOpenApi" @back="returnToWorkbench" />
</template>
