<script setup lang="ts">
import { computed } from 'vue';
import { useLanguageContext } from '@muyun/web-core';
import {
  ManagementExplorerColumn,
  TenantScopeExplorer,
  type QueryListRecord,
} from '@muyun/platform-components';
import type { TenantScopeController } from './useTenantScopeController';

defineOptions({ name: 'ModulePageTenantNavigator' });
const props = defineProps<{ controller: TenantScopeController; column?: boolean }>();
const language = useLanguageContext();
const explorer = computed(() => ({
  context: props.controller.tenantScopeContext.value!,
  selectedId: props.controller.selectedId.value || undefined,
  reloadKey: props.controller.tenantScopeReloadKey.value,
  disabled: props.controller.blocked.value,
}));
const events = {
  refresh: () => props.controller.refreshTenantScope(),
  loaded: (records: QueryListRecord[], full?: boolean, total?: number) =>
    props.controller.handleTenantScopeLoaded(records, full, total),
  select: (record: QueryListRecord) => props.controller.changeTenantScope(record),
  deselect: () => props.controller.changeTenantScope(undefined),
};
</script>

<template>
  <ManagementExplorerColumn
    v-if="column"
    collapsible
    :title="language.t('platform.screen.ModulePageHostRuntime.tenant', '租户')"
    :has-selection="Boolean(controller.selectedId.value)"
  >
    <TenantScopeExplorer v-bind="explorer" v-on="events" />
  </ManagementExplorerColumn>
  <TenantScopeExplorer v-else v-bind="explorer" v-on="events" />
</template>
