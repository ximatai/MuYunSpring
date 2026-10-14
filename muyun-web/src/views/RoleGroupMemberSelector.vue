<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
import { computed } from 'vue';
import { UiSelect } from '@muyun/vue-ui-antdv';
import type { OptionValue, OptionValueList, Role } from '@muyun/web-contracts';

const language = useLanguageContext();

defineOptions({ name: 'RoleGroupMemberSelector' });

const props = withDefaults(
  defineProps<{
    value?: string;
    candidates: Role[];
    currentRoleId?: string;
    disabled?: boolean;
  }>(),
  {
    value: undefined,
    currentRoleId: undefined,
    disabled: false,
  },
);

const emit = defineEmits<{
  'update:value': [value: string | undefined];
}>();

const selectedRoleIds = computed(() => parseRoleIds(props.value));
const selectedDataGrantRoleIds = computed(
  () =>
    new Set(
      props.candidates
        .filter((role) => role.roleKind === 'dataGrant' && role.id && selectedRoleIds.value.includes(role.id))
        .map((role) => role.id!),
    ),
);
const options = computed(() =>
  props.candidates
    .filter((role) => role.id && role.id !== props.currentRoleId)
    .map((role) => ({
      label: `${roleTitle(role)} / ${roleKindTitle(role.roleKind)}`,
      value: role.id!,
      disabled:
        role.roleKind === 'dataGrant' &&
        selectedDataGrantRoleIds.value.size > 0 &&
        !selectedRoleIds.value.includes(role.id!),
    })),
);

function updateValue(value: OptionValue | OptionValueList | null) {
  if (!Array.isArray(value)) {
    emit('update:value', undefined);
    return;
  }
  const normalized = value.map((item) => String(item).trim()).filter(Boolean);
  emit('update:value', normalized.length > 0 ? normalized.join(',') : undefined);
}

function parseRoleIds(value: unknown) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item).trim()).filter(Boolean);
  }
  if (typeof value !== 'string') {
    return [];
  }
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function roleTitle(record: Partial<Role> | undefined) {
  return String(record?.title ?? record?.id ?? language.t('platform.screen.RoleManagementView.role', '角色'));
}

function roleKindTitle(value: Role['roleKind']) {
  if (value === 'dataGrant') {
    return language.t('platform.screen.RoleManagementView.data-authorization-role', '数据授权角色');
  }
  return language.t('platform.screen.RoleManagementView.standard-role', '标准角色');
}
</script>

<template>
  <label class="role-group-member-selector">
    <span class="role-group-member-selector-label">{{
      language.t('platform.screen.RoleManagementView.member-roles', '成员角色')
    }}</span>
    <UiSelect
      mode="multiple"
      :value="selectedRoleIds"
      :options="options"
      :placeholder="
        language.t('platform.screen.RoleGroupMemberSelector.select-member-roles', '请选择成员角色')
      "
      :disabled="disabled"
      :allow-clear="true"
      @update:value="updateValue"
    />
  </label>
</template>

<style scoped>
.role-group-member-selector {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}

.role-group-member-selector-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
