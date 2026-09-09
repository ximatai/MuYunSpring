<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import RecordFormGrid from './RecordFormGrid.vue';
import RecordDetailFields from './RecordDetailFields.vue';
import RecordFieldLabel from './RecordFieldLabel.vue';
import { handlePlatformActionSuccess } from './platformActionResultFeedback';
import { presentPlatformError } from './platformErrorFeedback';
import { UiModal, UiSelect, UiCheckbox, UiSpin } from '@muyun/vue-ui-antdv';

const props = defineProps<{
  open: boolean;
  context: ModuleContext<import('./index').QueryListRecord>;
  recordId: string;
}>();
const emit = defineEmits<{ close: []; changed: [] }>();
type State = {
  version: number;
  ownerId?: string;
  assigneeIds: string[];
  memberIds: string[];
  titles: Record<string, string>;
};
const state = ref<State>();
const loading = ref(false);
const saving = ref(false);
const operation = ref('TRANSFER');
const relation = ref('ASSIGNEE');
const selected = ref<string[]>([]);
const previous = ref<string>();
const retain = ref(false);
const candidates = ref<Array<{ id: string; title: string }>>([]);
const candidateNames = ref<Record<string, string>>({});
let searchVersion = 0;
const path = computed(
  () => `/${encodeURIComponent(props.context.moduleAlias)}/permissions/${encodeURIComponent(props.recordId)}`,
);
const names = computed(() => ({
  ...state.value?.titles,
  ...candidateNames.value,
}));
const label = (id?: string) => (id ? (names.value[id] ?? id) : '未设置');
const currentIds = computed(() =>
  relation.value === 'ASSIGNEE' ? (state.value?.assigneeIds ?? []) : (state.value?.memberIds ?? []),
);
const options = computed(() =>
  (operation.value === 'REMOVE' ? currentIds.value : candidates.value.map((user) => user.id)).map((id) => ({
    value: id,
    label: label(id),
  })),
);
const valid = computed(
  () => !!state.value && selected.value.length > 0 && (operation.value !== 'REPLACE' || !!previous.value),
);
const summary = computed(() => {
  const target = selected.value.map((id) => label(id)).join('、');
  if (!target) return '';
  if (operation.value === 'TRANSFER')
    return `归属人：${label(state.value?.ownerId)} → ${target}；原归属人${retain.value ? '追加为相关人' : '不追加为相关人'}`;
  const kind = relation.value === 'ASSIGNEE' ? '负责人' : '相关人';
  if (operation.value === 'REPLACE') return `${kind}：${label(previous.value)} → ${target}`;
  return `${operation.value === 'ADD' ? '追加' : '移除'}${kind}：${target}`;
});
async function search(keyword = '') {
  const version = ++searchVersion;
  try {
    const result = await props.context.http.request<Array<{ id: string; title: string }>>({
      method: 'GET',
      path: `${path.value}/candidates`,
      query: { keyword },
    });
    if (version === searchVersion) {
      candidates.value = result;
      candidateNames.value = {
        ...candidateNames.value,
        ...Object.fromEntries(result.map((user) => [user.id, user.title])),
      };
    }
  } catch (cause) {
    presentPlatformError(cause, { source: 'record-permissions', phase: 'action' });
  }
}
watch([operation, relation], () => {
  selected.value = [];
  previous.value = undefined;
});
watch(
  () => [props.open, props.recordId] as const,
  async ([open]) => {
    if (!open) return;
    state.value = undefined;
    candidateNames.value = {};
    candidates.value = [];
    selected.value = [];
    previous.value = undefined;
    retain.value = false;
    loading.value = true;
    try {
      state.value = await props.context.http.request<State>({ method: 'GET', path: path.value });
      await search();
    } catch (cause) {
      presentPlatformError(cause, { source: 'record-permissions', phase: 'action' });
    } finally {
      loading.value = false;
    }
  },
  { immediate: true },
);
async function submit() {
  if (!valid.value || saving.value) return;
  saving.value = true;
  try {
    const result = await props.context.http.request({
      method: 'POST',
      path: path.value,
      body: {
        version: state.value!.version,
        operation: operation.value,
        relation: operation.value === 'TRANSFER' ? null : relation.value,
        userIds: selected.value,
        previousUserId: previous.value,
        retainPreviousOwner: operation.value === 'TRANSFER' ? retain.value : null,
      },
    });
    await handlePlatformActionSuccess(result, {
      source: 'record-permissions',
      phase: 'action',
      fallbackMessage: '记录权限已更新',
    });
    emit('changed');
    emit('close');
  } catch (cause) {
    presentPlatformError(cause, { source: 'record-permissions', phase: 'action' });
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <UiModal
    :open="open"
    title="授权"
    :width="640"
    :confirm-loading="saving"
    :confirm-disabled="loading || !valid"
    :closable="!saving"
    @confirm="submit"
    @cancel="!saving && emit('close')"
  >
    <UiSpin :spinning="loading">
      <div v-if="state" class="permission-content">
        <RecordDetailFields
          class="permission-current"
          :record="{
            owner: label(state.ownerId),
            assignees: state.assigneeIds.map((id) => label(id)).join('、'),
            members: state.memberIds.map((id) => label(id)).join('、'),
          }"
          :fallback="{
            owner: { label: '归属人' },
            assignees: { label: '负责人' },
            members: { label: '相关人' },
          }"
          empty-text="未设置"
        />
        <RecordFormGrid as="div">
          <label
            ><RecordFieldLabel required>操作</RecordFieldLabel
            ><UiSelect
              :value="operation"
              :disabled="saving"
              :allow-clear="false"
              :options="[
                { value: 'TRANSFER', label: '移交归属' },
                { value: 'ADD', label: '追加人员' },
                { value: 'REPLACE', label: '替换人员' },
                { value: 'REMOVE', label: '移除人员' },
              ]"
              @update:value="operation = String($event)"
          /></label>
          <label v-if="operation !== 'TRANSFER'"
            ><RecordFieldLabel required>人员关系</RecordFieldLabel
            ><UiSelect
              :value="relation"
              :disabled="saving"
              :allow-clear="false"
              :options="[
                { value: 'ASSIGNEE', label: '负责人' },
                { value: 'MEMBER', label: '相关人' },
              ]"
              @update:value="relation = String($event)"
          /></label>
          <label v-if="operation === 'REPLACE'"
            ><RecordFieldLabel required>原人员</RecordFieldLabel
            ><UiSelect
              :value="previous"
              placeholder="请选择原人员"
              :disabled="saving"
              :options="currentIds.map((id) => ({ value: id, label: label(id) }))"
              @update:value="previous = $event == null ? undefined : String($event)"
          /></label>
          <label
            ><RecordFieldLabel required>{{
              operation === 'REMOVE' ? '移除人员' : '目标人员'
            }}</RecordFieldLabel
            ><UiSelect
              :key="operation"
              :placeholder="
                operation === 'REMOVE'
                  ? currentIds.length
                    ? '请选择移除人员'
                    : '暂无可移除人员'
                  : '搜索并选择人员'
              "
              :value="['ADD', 'REMOVE'].includes(operation) ? selected : selected[0]"
              :mode="['ADD', 'REMOVE'].includes(operation) ? 'multiple' : undefined"
              :options="options"
              :disabled="saving"
              show-search
              :filter-option="operation === 'REMOVE'"
              @search="operation !== 'REMOVE' && search($event)"
              @update:value="
                selected = $event == null ? [] : (Array.isArray($event) ? $event : [$event]).map(String)
              "
          /></label>
        </RecordFormGrid>
        <UiCheckbox
          v-if="operation === 'TRANSFER'"
          :checked="retain"
          :disabled="saving"
          @update:checked="retain = $event"
          >将原归属人追加为相关人</UiCheckbox
        >
        <p v-if="summary" class="permission-summary">{{ summary }}</p>
      </div>
    </UiSpin>
  </UiModal>
</template>

<style scoped>
.permission-content {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.permission-current {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--muyun-border-subtle);
}
.permission-summary {
  margin: 0;
  color: var(--muyun-text-secondary);
  font-size: 13px;
  line-height: 20px;
  overflow-wrap: anywhere;
}
</style>
