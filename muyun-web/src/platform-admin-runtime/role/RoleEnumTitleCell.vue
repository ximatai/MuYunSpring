<script setup lang="ts">
import { computed } from 'vue';
import type { QueryListRecord, RecordQueryListColumn } from '@muyun/platform-components';

defineOptions({ name: 'RoleEnumTitleCell' });

const props = defineProps<{ record: QueryListRecord; column?: RecordQueryListColumn }>();

const title = computed(() => {
  const value = String(props.record[props.column?.key ?? ''] ?? '');
  const titles: Record<string, string> = {
    account: '账号角色',
    employment: '任职角色',
    standard: '标准角色',
    group: '角色组',
    dataGrant: '数据授权角色',
    system: '系统角色',
    private: '私有',
    ownerAndChildren: '本级及下级',
    tenant: '租户公开',
    platform: '全局公开',
  };
  return titles[value] ?? (value || '-');
});
</script>

<template>
  <span>{{ title }}</span>
</template>
