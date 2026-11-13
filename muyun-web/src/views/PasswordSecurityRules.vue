<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useLanguageContext, type ModuleContext } from '@muyun/web-core';
import { UiActionButton, UiDropdown, type UiDropdownItem } from '@muyun/vue-ui-antdv';
import {
  RecordQueryListSurface,
  RecordModeDrawer,
  RecordFormFields,
  RecordStatusTag,
  confirmAction,
  type QueryListRecord,
  type RecordFormFieldValue,
} from '@muyun/platform-components';

defineOptions({ name: 'PasswordSecurityRules' });
const props = defineProps<{
  rows: QueryListRecord[];
  originalRows: QueryListRecord[];
  targetContext: ModuleContext<QueryListRecord>;
  editing: boolean;
  disabled: boolean;
}>();
const emit = defineEmits<{
  'update:rows': [rows: QueryListRecord[]];
  'update:editing': [editing: boolean];
}>();
const language = useLanguageContext();
const open = ref(false);
watch(open, (value) => emit('update:editing', value), { flush: 'sync' });
onBeforeUnmount(() => emit('update:editing', false));
const mode = ref<'create' | 'edit'>('create');
const draft = ref<QueryListRecord>({});
const original = ref('');
const activeIndex = ref(-1);
const sortOnly = ref(false);
const formValidity = ref<{ valid: boolean; pending?: boolean }>({ valid: false });
const validationRequestKey = ref(0);
const formSessionKey = ref(0);
const fields = computed(() =>
  sortOnly.value
    ? ['sortOrder']
    : [
        'title',
        'pattern',
        'message',
        ...(mode.value === 'create' || permitted('sort', draft.value) ? ['sortOrder'] : []),
        'description',
      ],
);
const fallback = computed(() => ({
  title: { label: language.t('platform.password-security.rule-name', '规则名称'), required: true },
  pattern: { label: language.t('platform.password-security.rule-pattern', '正则表达式'), required: true },
  message: { label: language.t('platform.password-security.rule-message', '失败提示'), required: true },
  sortOrder: {
    label: language.t('platform.password-security.rule-sort-order', '排序号'),
    controlType: 'numberInput' as const,
  },
  description: {
    label: language.t('platform.password-security.rule-description', '说明'),
    controlType: 'textarea' as const,
  },
}));
const columns = computed(() => [
  { key: 'title', dataIndex: 'title', title: language.t('platform.password-security.rule-name', '规则名称') },
  {
    key: 'message',
    dataIndex: 'message',
    title: language.t('platform.password-security.rule-message', '失败提示'),
  },
  {
    key: 'sortOrder',
    dataIndex: 'sortOrder',
    title: language.t('platform.password-security.rule-sort-order', '排序号'),
    width: '90px',
  },
  {
    key: 'enabled',
    dataIndex: 'enabled',
    title: language.t('platform.password-security.rule-status', '状态'),
    width: '90px',
  },
]);
const rows = computed(() => props.rows.map((row, index) => ({ ...row, draftIndex: index })));
function permitted(action: string, record?: QueryListRecord) {
  if (!props.editing || props.disabled) return false;
  if (record?.id == null) return props.targetContext.can('create') === true;
  // Reversing a staged toggle only restores the loaded record; it is not a new server action.
  if (action === 'enable' || action === 'disable') {
    const original = props.originalRows.find((item) => item.id === record.id);
    if (original && (original.enabled === false) !== (record.enabled === false)) return true;
  }
  return props.targetContext.can(action, String(record.id)) === true;
}
function create() {
  if (!permitted('create')) return;
  mode.value = 'create';
  activeIndex.value = -1;
  sortOnly.value = false;
  draft.value = { enabled: true, sortOrder: 0 };
  original.value = JSON.stringify(draft.value);
  validationRequestKey.value = 0;
  formSessionKey.value++;
  open.value = true;
}
function edit(row: QueryListRecord, sorting = false) {
  if (!permitted(sorting ? 'sort' : 'update', row)) return;
  mode.value = 'edit';
  activeIndex.value = Number(row.draftIndex);
  sortOnly.value = sorting;
  const record = { ...row };
  delete record.draftIndex;
  draft.value = { ...record };
  original.value = JSON.stringify(draft.value);
  validationRequestKey.value = 0;
  formSessionKey.value++;
  open.value = true;
}
function setField(name: string, value: RecordFormFieldValue) {
  draft.value = { ...draft.value, [name]: value };
}
async function beforeClose() {
  if (original.value === JSON.stringify(draft.value)) return true;
  return confirmAction(
    {
      title: language.t('platform.password-security.discard-title', '放弃未保存的修改？'),
      content: language.t('platform.password-security.discard-content', '规则修改尚未保存。'),
    },
    language,
  );
}
function save() {
  if (props.disabled) return;
  validationRequestKey.value++;
  if (!formValidity.value.valid || formValidity.value.pending) return;
  const next = props.rows.map((row) => ({ ...row }));
  if (mode.value === 'create') next.push({ ...draft.value });
  else next[activeIndex.value] = { ...draft.value };
  emit('update:rows', next);
  open.value = false;
}
async function cancel() {
  if (await beforeClose()) open.value = false;
}
function rowActions(row: QueryListRecord): UiDropdownItem[] {
  const actions: UiDropdownItem[] = [];
  if (permitted('update', row))
    actions.push({ key: 'edit', title: language.t('platform.action.edit', '编辑') });
  else if (permitted('sort', row))
    actions.push({ key: 'sort', title: language.t('platform.action.sort', '排序') });
  const toggle = row.enabled === false ? 'enable' : 'disable';
  if (permitted(toggle, row))
    actions.push({
      key: toggle,
      title:
        row.enabled === false
          ? language.t('platform.action.enable', '启用')
          : language.t('platform.action.disable', '停用'),
    });
  if (permitted('delete', row))
    actions.push({ key: 'delete', title: language.t('platform.action.delete', '删除'), danger: true });
  return actions;
}
function executeRowAction(row: QueryListRecord, key: string) {
  if (key === 'edit' || key === 'sort') edit(row, key === 'sort');
  else if (key === 'delete') void remove(row);
  else if (key === 'enable' || key === 'disable') toggle(row);
}
function toggle(row: QueryListRecord) {
  const action = row.enabled === false ? 'enable' : 'disable';
  if (!permitted(action, row)) return;
  const next = props.rows.map((item, index) =>
    index === Number(row.draftIndex) ? { ...item, enabled: row.enabled === false } : item,
  );
  emit('update:rows', next);
}
async function remove(row: QueryListRecord) {
  if (!permitted('delete', row)) return;
  if (
    !(await confirmAction(
      {
        title: language.t('platform.password-security.delete-rule', '删除密码规则？'),
        content: String(row.title ?? ''),
      },
      language,
    ))
  )
    return;
  emit(
    'update:rows',
    props.rows.filter((_, index) => index !== Number(row.draftIndex)),
  );
}
watch(
  () => props.editing,
  (editing) => {
    if (!editing) open.value = false;
  },
);
</script>

<template>
  <RecordQueryListSurface
    :columns="columns"
    :rows="rows"
    :row-key="(row) => String(row.draftIndex)"
    :header-visible="editing"
    :show-title="false"
    :show-action-column="editing"
    :action-column-width="112"
    :fill-height="false"
    :conditions-visible="false"
    embedded
    :chrome-free="!editing"
  >
    <template #operations>
      <UiActionButton v-if="permitted('create')" density="compact" @click="create">
        {{ language.t('platform.action.create', '新增') }}
      </UiActionButton>
    </template>
    <template #cell="{ column, value }">
      <RecordStatusTag v-if="column.key === 'enabled'" :enabled="value !== false" />
      <span v-else>{{ value }}</span>
    </template>
    <template #rowActions="{ record }">
      <div class="rule-actions">
        <UiActionButton
          v-if="rowActions(record)[0]"
          emphasis="quiet"
          density="compact"
          :intent="rowActions(record)[0].danger ? 'danger' : 'normal'"
          @click="executeRowAction(record, rowActions(record)[0].key)"
        >
          {{ rowActions(record)[0].title }}
        </UiActionButton>
        <UiDropdown
          v-if="rowActions(record).length > 1"
          :items="rowActions(record).slice(1)"
          @select="executeRowAction(record, $event)"
        >
          <UiActionButton
            emphasis="quiet"
            density="compact"
            icon-name="down"
            :title="language.t('platform.screen.RecordQueryListPanel.more', '更多')"
          />
        </UiDropdown>
      </div>
    </template>
  </RecordQueryListSurface>
  <RecordModeDrawer
    :open="open"
    :title="
      mode === 'create'
        ? language.t('platform.password-security.new-rule', '新建密码规则')
        : language.t('platform.password-security.edit-rule', '编辑密码规则')
    "
    :mode="mode"
    :saving="false"
    :save-available="true"
    :before-close="beforeClose"
    @close="open = false"
    @save="save"
  >
    <template #form>
      <p class="rule-draft-note">
        {{
          language.t(
            'platform.password-security.rule-draft-note',
            '规则修改仅加入当前草稿，点击页面“保存”后生效。',
          )
        }}
      </p>
      <RecordFormFields
        :record="draft"
        :mode="mode"
        :fallback="fallback"
        :field-names="fields"
        :disabled="disabled"
        :form-session-key="formSessionKey"
        :validation-request-key="validationRequestKey"
        @validity-change="formValidity = $event"
        @update:field="setField"
      />
    </template>
    <template #operation>
      <UiActionButton :disabled="disabled" @click="cancel">
        {{ language.t('platform.action.cancel', '取消') }}
      </UiActionButton>
      <UiActionButton emphasis="primary" :disabled="disabled" @click="save">
        {{ language.t('platform.action.confirm', '确定') }}
      </UiActionButton>
    </template>
  </RecordModeDrawer>
</template>

<style scoped>
.rule-actions {
  display: flex;
  align-items: center;
  white-space: nowrap;
}
.rule-draft-note {
  margin: 0 0 var(--muyun-detail-section-block-gap, 16px);
  color: var(--muyun-text-muted);
  font-size: var(--muyun-font-size-helper, 12px);
}
</style>
