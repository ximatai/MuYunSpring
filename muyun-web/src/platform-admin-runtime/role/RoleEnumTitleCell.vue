<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import type { QueryListRecord, RecordQueryListColumn } from '@muyun/platform-components';

const language = useLanguageContext();

defineOptions({ name: 'RoleEnumTitleCell' });

const props = defineProps<{ record: QueryListRecord; column?: RecordQueryListColumn }>();

const title = computed(() => {
  const value = String(props.record[props.column?.key ?? ''] ?? '');
  const titles: Record<string, string> = {
    account: language.t('platform.screen.RoleManagementView.account-role', '账号角色'),
    employment: language.t('platform.screen.RoleManagementView.position-role', '任职角色'),
    standard: language.t('platform.screen.RoleManagementView.standard-role', '标准角色'),
    group: language.t('platform.screen.RoleManagementView.role-group', '角色组'),
    dataGrant: language.t('platform.screen.RoleManagementView.data-authorization-role', '数据授权角色'),
    system: language.t('platform.screen.RoleManagementView.system-role', '系统角色'),
    private: language.t('platform.screen.RoleManagementView.private', '私有'),
    ownerAndChildren: language.t(
      'platform.screen.RoleManagementView.current-scope-and-descendants',
      '本级及下级',
    ),
    tenant: language.t('platform.screen.RoleManagementView.visible-within-tenant', '租户公开'),
    platform: language.t('platform.screen.RoleManagementView.globally-visible', '全局公开'),
  };
  return titles[value] ?? (value || '-');
});
</script>

<template>
  <span>{{ title }}</span>
</template>
