<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import {
  RecordDetailDrawer,
  handlePlatformActionSuccess,
  presentPlatformError,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin } from '@muyun/vue-ui-antdv';
import type { UiDataTablePagination } from '@muyun/vue-ui-antdv';
import type { EmploymentRoleGrant, EmploymentSelectorItem, Role } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import type { ModulePageDrawerContext } from '@muyun/dynamic-page-runtime';
import { createRoleGrantClient } from './roleGrantClient';
import EmployeeEmploymentTable from './EmployeeEmploymentTable.vue';

defineOptions({ name: 'RoleEmploymentGrantDrawer' });
const props = defineProps<{
  open: boolean;
  container?: HTMLElement | null;
  /** Uses the owning platform drawer and renders only this operation's content. */
  embedded?: boolean;
  drawerContext?: ModulePageDrawerContext;
  context: ModuleContext<Role>;
  role?: Role;
}>();
const emit = defineEmits<{ close: []; saved: [] }>();
const grants = ref<EmploymentRoleGrant[]>([]);
const rows = ref<EmploymentSelectorItem[]>([]);
const employmentsById = ref<Record<string, EmploymentSelectorItem>>({});
const selected = ref<Set<string>>(new Set());
const original = ref<Set<string>>(new Set());
const selectionInitialized = ref(false);
const loading = ref(false);
const saving = ref(false);
const failed = ref(false);
const pageNum = ref(1);
const total = ref(0);
const keyword = ref('');
const roleId = computed(() => props.role?.id);
const client = computed(() => createRoleGrantClient(props.context.http));
const title = computed(() => `绑定任职 - ${props.role?.title ?? props.role?.id ?? ''}`);
const contentContainer = computed(() => (props.embedded ? 'section' : RecordDetailDrawer));
const contentContainerProps = computed(() =>
  props.embedded
    ? {}
    : {
        open: props.open,
        title: title.value,
        renderMode: props.container ? 'inline' : 'portal',
        closeTitle: '关闭',
        onClose: () => !saving.value && emit('close'),
      },
);
const added = computed(() => [...selected.value].filter((id) => !original.value.has(id)));
const removed = computed(() => [...original.value].filter((id) => !selected.value.has(id)));
const grantByPosition = computed(
  () => new Map(grants.value.map((grant) => [grant.employeePositionId, grant])),
);
const displayRows = computed(() =>
  rows.value.filter((row) => !keyword.value.trim() || rowText(row).includes(keyword.value.trim())),
);
const selectedEmployments = computed(() =>
  [...selected.value].map(
    (employmentId) => employmentsById.value[employmentId] ?? employmentFallback(employmentId),
  ),
);
const employmentTablePagination = computed<UiDataTablePagination>(() => ({
  current: pageNum.value,
  total: total.value,
  pageSize: 50,
  showSizeChanger: false,
  showQuickJumper: false,
  onChange: (page) => void load(page),
}));
watch(
  () => [props.open, props.role?.id],
  ([open]) => {
    if (open) void load();
    else reset();
  },
  { immediate: true },
);
watch(
  [() => props.embedded, () => props.drawerContext, selected, added, removed, saving, loading],
  configureDrawerPresentation,
  { immediate: true },
);
onBeforeUnmount(() => props.drawerContext?.setOperation(undefined));

function configureDrawerPresentation() {
  const drawer = props.drawerContext;
  if (!props.embedded || !drawer) return;
  drawer.setSubtitle(`角色：${props.role?.title ?? props.role?.id ?? '角色'} · ${scopeTitle(props.role)}`);
  drawer.setOperation({
    summary: `已选 ${selected.value.size} 个任职${added.value.length || removed.value.length ? ` · 新增 ${added.value.length} · 移除 ${removed.value.length}` : ''}`,
    actions: [
      {
        key: 'cancel-employment-role-grants',
        label: '取消',
        disabled: saving.value,
        run: () => emit('close'),
      },
      {
        key: 'save-employment-role-grants',
        label: '确定',
        emphasis: 'primary',
        disabled: loading.value || (added.value.length === 0 && removed.value.length === 0),
        loading: saving.value,
        run: () => void save(),
      },
    ],
  });
}
async function load(page = 1) {
  const id = roleId.value;
  if (!id) return;
  loading.value = true;
  failed.value = false;
  try {
    const [nextGrants, response] = await Promise.all([
      client.value.employmentRoleGrants(id),
      client.value.employmentSelector(id, {
        enabledOnly: true,
        page: { pageNum: page, pageSize: 50 },
      }),
    ]);
    grants.value = nextGrants;
    original.value = new Set(
      nextGrants.map((grant) => grant.employeePositionId).filter((id): id is string => Boolean(id)),
    );
    employmentsById.value = {
      ...employmentsById.value,
      ...mergeEmployments(response.records),
    };
    if (!selectionInitialized.value) {
      selected.value = new Set(original.value);
      selectionInitialized.value = true;
    }
    rows.value = response.records;
    pageNum.value = response.pageNum;
    total.value = response.total;
  } catch (cause) {
    failed.value = true;
    presentPlatformError(cause, { source: 'role-employment-grants', phase: 'load' });
  } finally {
    loading.value = false;
  }
}
async function save() {
  const id = roleId.value;
  if (!id) return;
  saving.value = true;
  try {
    for (const positionId of added.value) await client.value.grantEmploymentRole(id, positionId);
    for (const positionId of removed.value) {
      const grantId = grantByPosition.value.get(positionId)?.id;
      if (grantId) await client.value.deleteEmploymentRoleGrant(id, grantId);
    }
    await handlePlatformActionSuccess(
      { success: true, message: '任职角色授权已保存' },
      { source: 'role-employment-grants', phase: 'action' },
    );
    emit('saved');
    emit('close');
  } catch (cause) {
    presentPlatformError(cause, { source: 'role-employment-grants', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
function reset() {
  grants.value = [];
  rows.value = [];
  employmentsById.value = {};
  selected.value = new Set();
  original.value = new Set();
  selectionInitialized.value = false;
  keyword.value = '';
  pageNum.value = 1;
  total.value = 0;
  failed.value = false;
}
function rowText(row: EmploymentSelectorItem) {
  return [row.employeeTitle, row.employeeNo, row.organizationTitle, row.departmentTitle, row.positionTitle]
    .filter(Boolean)
    .join(' ');
}

function removeSelectedEmployment(employmentId: string) {
  const next = new Set(selected.value);
  next.delete(employmentId);
  selected.value = next;
}

function mergeEmployments(employments: EmploymentSelectorItem[]) {
  const next: Record<string, EmploymentSelectorItem> = {};
  for (const employment of employments) {
    if (employment.id) next[employment.id] = employment;
  }
  return next;
}

function employmentFallback(id: string): EmploymentSelectorItem {
  return { id, positionId: id };
}

function selectedEmploymentTitle(employment: EmploymentSelectorItem) {
  const position = employment.positionTitle ?? employment.positionId ?? employment.id;
  return employment.employeeTitle ? `${employment.employeeTitle} · ${position}` : position;
}

function selectedEmploymentDescription(employment: EmploymentSelectorItem) {
  return (
    [
      employment.employeeNo,
      employment.organizationTitle ?? employment.organizationId,
      employment.departmentTitle ?? employment.departmentId,
    ]
      .filter(Boolean)
      .join(' / ') || employment.id
  );
}

function scopeTitle(role: Role | undefined) {
  if (role?.ownerScopeType === 'platform') return '平台范围';
  if (role?.ownerScopeType === 'organization') return '机构范围';
  return '租户范围';
}
</script>
<template>
  <component
    :is="contentContainer"
    v-bind="contentContainerProps"
    :class="{ 'role-employment-grant-drawer-surface': embedded }"
  >
    <template v-if="!embedded" #operation>
      <UiButton :disabled="saving" @click="emit('close')">取消</UiButton>
      <UiButton
        type="primary"
        :loading="saving"
        :disabled="loading || (added.length === 0 && removed.length === 0)"
        @click="save"
      >
        确定
      </UiButton>
    </template>
    <UiSpin v-if="loading" tip="加载任职" />
    <div v-else-if="failed">
      <UiError title="任职加载失败" message="无法加载任职授权" /><UiButton @click="load()">重试</UiButton>
    </div>
    <section v-else class="role-employment-grant-body">
      <UiInput v-model:value="keyword" allow-clear placeholder="筛选职员、工号、机构、部门或岗位" />
      <EmployeeEmploymentTable
        v-model:selected-ids="selected"
        :rows="displayRows"
        selectable
        :disabled="saving"
        :pagination="employmentTablePagination"
        fill-height
      />
      <section v-if="selectedEmployments.length > 0" class="role-employment-grant-selected">
        <div class="role-employment-grant-selected-title">
          <strong>已选任职</strong>
          <span>{{ selectedEmployments.length }} 个</span>
        </div>
        <div class="role-employment-grant-selected-list">
          <button
            v-for="employment in selectedEmployments"
            :key="employment.id"
            type="button"
            :disabled="saving"
            @click="removeSelectedEmployment(employment.id)"
          >
            <span>{{ selectedEmploymentTitle(employment) }}</span>
            <small>{{ selectedEmploymentDescription(employment) }}</small>
          </button>
        </div>
      </section>
    </section>
  </component>
</template>
<style scoped>
.role-employment-grant-drawer-surface {
  height: 100%;
  min-height: 0;
}

.role-employment-grant-body {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  height: 100%;
  min-height: 0;
}
.role-employment-grant-selected {
  display: grid;
  gap: 8px;
  max-block-size: 168px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}
.role-employment-grant-selected-title {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.role-employment-grant-selected-title {
  justify-content: space-between;
}
.role-employment-grant-selected-title strong {
  color: var(--muyun-text);
  font-size: 13px;
}
.role-employment-grant-selected-title span,
.role-employment-grant-selected-empty {
  color: var(--muyun-text-muted);
  font-size: 12px;
}
.role-employment-grant-selected-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  overflow: auto;
}
.role-employment-grant-selected-list button {
  display: inline-grid;
  max-width: 220px;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 6px;
  background: var(--muyun-surface);
  color: var(--muyun-text-muted);
  text-align: left;
  cursor: pointer;
}
.role-employment-grant-selected-list button:disabled {
  cursor: not-allowed;
}
.role-employment-grant-selected-list span,
.role-employment-grant-selected-list small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-employment-grant-selected-list span {
  color: var(--muyun-text);
  font-size: 12px;
}
.role-employment-grant-selected-list small {
  font-size: 11px;
}
</style>
