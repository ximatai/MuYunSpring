<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { UiSelect } from '@muyun/vue-ui-antdv';
import type { OptionValue, OptionValueList, Role } from '@muyun/web-contracts';
import type { ModulePageFormContributionContext } from '@muyun/dynamic-page-runtime';

defineOptions({ name: 'RoleGroupMemberFormField' });

const props = defineProps<{ context: ModulePageFormContributionContext }>();
const candidates = ref<Role[]>([]);
const loading = ref(false);
const groupRole = computed(() => props.context.draft.roleKind === 'group');
const selectedRoleIds = computed(() => parseRoleIds(props.context.draft.memberRoleIds));
const currentRoleId = computed(() => String(props.context.draft.id ?? '') || undefined);
const selectedDataGrantRoleIds = computed(() => new Set(
  candidates.value
    .filter((role) => role.roleKind === 'dataGrant' && role.id && selectedRoleIds.value.includes(role.id))
    .map((role) => role.id!),
));
const options = computed(() => candidates.value
  .filter((role) => role.id && role.id !== currentRoleId.value)
  .map((role) => ({
    label: `${role.title ?? role.id} / ${role.roleKind === 'dataGrant' ? '数据授权角色' : '标准角色'}`,
    value: role.id!,
    disabled: role.roleKind === 'dataGrant'
      && selectedDataGrantRoleIds.value.size > 0
      && !selectedRoleIds.value.includes(role.id!),
  })));

watch([groupRole, () => props.context.formSessionKey], () => void loadCandidates(), { immediate: true });

async function loadCandidates() {
  if (!groupRole.value) {
    candidates.value = [];
    return;
  }
  loading.value = true;
  try {
    const response = await props.context.queryRecords({
      page: { pageNum: 1, pageSize: 500 },
      conditions: [
        { fieldName: 'assignmentType', operator: 'EQ', values: ['employment'] },
        { fieldName: 'roleKind', operator: 'IN', values: ['standard', 'dataGrant'] },
        { fieldName: 'enabled', operator: 'EQ', values: [true] },
      ],
      sorts: [{ field: 'sortOrder' }, { field: 'title' }],
    });
    candidates.value = response.records as Role[];
  } finally {
    loading.value = false;
  }
}

function updateValue(value: OptionValue | OptionValueList | null) {
  const ids = Array.isArray(value) ? value.map((item) => String(item).trim()).filter(Boolean) : [];
  props.context.setField('memberRoleIds', ids.length > 0 ? ids.join(',') : undefined);
}

function parseRoleIds(value: unknown) {
  return typeof value === 'string' ? value.split(',').map((item) => item.trim()).filter(Boolean) : [];
}
</script>

<template>
  <label v-if="groupRole" class="role-group-member-form-field">
    <span>成员角色</span>
    <UiSelect
      mode="multiple"
      :value="selectedRoleIds"
      :options="options"
      :loading="loading"
      placeholder="请选择成员角色"
      :disabled="context.mode === 'view'"
      allow-clear
      @update:value="updateValue"
    />
  </label>
</template>

<style scoped>
.role-group-member-form-field { display: grid; gap: 6px; color: var(--muyun-text-muted); font-size: 13px; }
</style>
