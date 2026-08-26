<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  RecordDetailDrawer,
  handlePlatformActionSuccess,
  presentPlatformError,
} from '@muyun/platform-components';
import { UiButton, UiError, UiInput, UiSpin } from '@muyun/vue-ui-antdv';
import type { EmploymentRoleGrant, EmploymentSelectorItem, Role } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';
import { createRoleGrantClient } from './roleGrantClient';
import EmployeeEmploymentTable from './EmployeeEmploymentTable.vue';

defineOptions({ name: 'RoleEmploymentGrantDrawer' });
const props = defineProps<{
  open: boolean;
  container?: HTMLElement | null;
  /** Uses the owning platform drawer and renders only this operation's content. */
  embedded?: boolean;
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
const selectedEmployeeCount = computed(
  () =>
    new Set(displayRows.value.filter((row) => selected.value.has(row.id)).map((row) => row.employeeId)).size,
);
const selectedEmployments = computed(() =>
  [...selected.value].map(
    (employmentId) => employmentsById.value[employmentId] ?? employmentFallback(employmentId),
  ),
);
watch(
  () => [props.open, props.role?.id],
  ([open]) => {
    if (open) void load();
    else reset();
  },
  { immediate: true },
);
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
</script>
<template>
  <component :is="contentContainer" v-bind="contentContainerProps">
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
      <p>角色将在所选任职的机构、部门和岗位上下文中生效。展开职员后选择其具体任职。</p>
      <UiInput v-model:value="keyword" allow-clear placeholder="筛选职员、工号、机构、部门或岗位" />
      <div>
        已选 {{ selected.size }} 个任职；当前页涉及 {{ selectedEmployeeCount }} 名职员；新增
        {{ added.length }} 个，移除 {{ removed.length }} 个
      </div>
      <section class="role-employment-grant-selected">
        <div class="role-employment-grant-selected-title">
          <strong>已选任职</strong>
          <span>{{ selectedEmployments.length }} 个</span>
        </div>
        <div v-if="selectedEmployments.length > 0" class="role-employment-grant-selected-list">
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
        <span v-else class="role-employment-grant-selected-empty">暂无已选任职</span>
      </section>
      <EmployeeEmploymentTable
        v-model:selected-ids="selected"
        :rows="displayRows"
        selectable
        :disabled="saving"
      />
      <div class="role-employment-grant-pagination">
        <span>共 {{ total }} 个启用任职</span>
        <UiButton :disabled="loading || pageNum <= 1" @click="load(pageNum - 1)">上一页</UiButton>
        <UiButton :disabled="loading || pageNum * 50 >= total" @click="load(pageNum + 1)"> 下一页 </UiButton>
      </div>
    </section>
    <footer v-if="embedded" class="role-employment-grant-operation">
      <UiButton :disabled="saving" @click="emit('close')">取消</UiButton>
      <UiButton
        type="primary"
        :loading="saving"
        :disabled="loading || (added.length === 0 && removed.length === 0)"
        @click="save"
      >
        确定
      </UiButton>
    </footer>
  </component>
</template>
<style scoped>
.role-employment-grant-body {
  display: grid;
  gap: 12px;
}
.role-employment-grant-operation {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
.role-employment-grant-body p {
  margin: 0;
  color: var(--muyun-text-muted);
}
.role-employment-grant-selected {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--muyun-border-subtle);
  border-radius: 8px;
  background: var(--muyun-hover-subtle);
}
.role-employment-grant-selected-title,
.role-employment-grant-pagination {
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
