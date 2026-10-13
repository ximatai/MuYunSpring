<script setup lang="ts">
import { useLanguageContext } from '@muyun/web-core';
const language = useLanguageContext();
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
const label = (id?: string) =>
  id ? (names.value[id] ?? id) : language.t('platform.screen.RecordPermissionDialog.not-set', '未设置');
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
    return language.t(
      'platform.permissions.transfer-summary',
      '归属人：{previous} → {target}；原归属人{retained}',
      {
        previous: label(state.value?.ownerId),
        target,
        retained: retain.value
          ? language.t('platform.screen.RecordPermissionDialog.added-as-a-related-person', '追加为相关人')
          : language.t(
              'platform.screen.RecordPermissionDialog.not-added-as-a-related-person',
              '不追加为相关人',
            ),
      },
    );
  const kind =
    relation.value === 'ASSIGNEE'
      ? language.t('platform.screen.RecordPermissionDialog.assignees', '负责人')
      : language.t('platform.screen.RecordPermissionDialog.related-people', '相关人');
  if (operation.value === 'REPLACE') return `${kind}：${label(previous.value)} → ${target}`;
  return `${operation.value === 'ADD' ? language.t('platform.screen.RecordPermissionDialog.add', '追加') : language.t('platform.screen.RecordPermissionDialog.remove', '移除')}${kind}：${target}`;
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
      fallbackMessage: language.t(
        'platform.screen.RecordPermissionDialog.record-permissions-updated',
        '记录权限已更新',
      ),
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
    :title="language.t('platform.screen.RecordPermissionDialog.permissions', '授权')"
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
            owner: { label: language.t('platform.screen.RecordPermissionDialog.owner', '归属人') },
            assignees: { label: language.t('platform.screen.RecordPermissionDialog.assignees', '负责人') },
            members: { label: language.t('platform.screen.RecordPermissionDialog.related-people', '相关人') },
          }"
          empty-text="未设置"
        />
        <RecordFormGrid as="div">
          <label
            ><RecordFieldLabel required>{{
              language.t('platform.screen.RecordPermissionDialog.operation', '操作')
            }}</RecordFieldLabel
            ><UiSelect
              :value="operation"
              :disabled="saving"
              :allow-clear="false"
              :options="[
                {
                  value: 'TRANSFER',
                  label: language.t('platform.screen.RecordPermissionDialog.transfer-ownership', '移交归属'),
                },
                {
                  value: 'ADD',
                  label: language.t('platform.screen.RecordPermissionDialog.add-people', '追加人员'),
                },
                {
                  value: 'REPLACE',
                  label: language.t('platform.screen.RecordPermissionDialog.replace-people', '替换人员'),
                },
                {
                  value: 'REMOVE',
                  label: language.t('platform.screen.RecordPermissionDialog.remove-people', '移除人员'),
                },
              ]"
              @update:value="operation = String($event)"
          /></label>
          <label v-if="operation !== 'TRANSFER'"
            ><RecordFieldLabel required>{{
              language.t('platform.screen.RecordPermissionDialog.person-relationship', '人员关系')
            }}</RecordFieldLabel
            ><UiSelect
              :value="relation"
              :disabled="saving"
              :allow-clear="false"
              :options="[
                {
                  value: 'ASSIGNEE',
                  label: language.t('platform.screen.RecordPermissionDialog.assignees', '负责人'),
                },
                {
                  value: 'MEMBER',
                  label: language.t('platform.screen.RecordPermissionDialog.related-people', '相关人'),
                },
              ]"
              @update:value="relation = String($event)"
          /></label>
          <label v-if="operation === 'REPLACE'"
            ><RecordFieldLabel required>{{
              language.t('platform.screen.RecordPermissionDialog.previous-person', '原人员')
            }}</RecordFieldLabel
            ><UiSelect
              :value="previous"
              :placeholder="
                language.t(
                  'platform.screen.RecordPermissionDialog.select-the-previous-person',
                  '请选择原人员',
                )
              "
              :disabled="saving"
              :options="currentIds.map((id) => ({ value: id, label: label(id) }))"
              @update:value="previous = $event == null ? undefined : String($event)"
          /></label>
          <label
            ><RecordFieldLabel required>{{
              operation === 'REMOVE'
                ? language.t('platform.screen.RecordPermissionDialog.remove-people', '移除人员')
                : '目标人员'
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
          >{{
            language.t(
              'platform.screen.RecordPermissionDialog.add-the-previous-owner-as-a-related',
              '将原归属人追加为相关人',
            )
          }}</UiCheckbox
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
