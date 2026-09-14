<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  UiButton,
  UiDataTable,
  UiError,
  UiModal,
  UiRecordExplorerItem,
  UiSearchInput,
  UiTagList,
} from '@muyun/vue-ui-antdv';
import ObjectPickerInput from './ObjectPickerInput.vue';
import RecordExplorerPanel from './RecordExplorerPanel.vue';
import type { UiDataTableColumn, UiDataTableRecord, UiDataTableSelection } from '@muyun/vue-ui-antdv';
import {
  normalizeUserAccountIds,
  userPickerValue,
  type UserAccountId,
  type UserPickerCandidate,
  type UserPickerNavigationItem,
  type UserPickerNavigationScope,
  type UserPickerPageSearch,
  type UserPickerResolver,
} from './userPickerModel';

defineOptions({ name: 'UserPicker' });

const props = withDefaults(
  defineProps<{
    /** User account ID for a single picker, or account IDs for a multiple picker. */
    value?: UserAccountId | readonly UserAccountId[];
    multiple?: boolean;
    maxSelection?: number;
    placeholder?: string;
    disabled?: boolean;
    pageSize?: number;
    title?: string;
    searchPlaceholder?: string;
    emptyDescription?: string;
    selectionNoun?: string;
    searchPage: UserPickerPageSearch;
    resolveUsers: UserPickerResolver;
  }>(),
  {
    value: undefined,
    multiple: false,
    maxSelection: undefined,
    placeholder: '搜索并选择用户',
    disabled: false,
    pageSize: 20,
    title: '选择用户',
    searchPlaceholder: '按账号或用户 ID 搜索',
    emptyDescription: '没有可选择的用户',
    selectionNoun: '用户',
  },
);

const emit = defineEmits<{
  'update:value': [value: UserAccountId | UserAccountId[] | undefined];
  select: [users: UserPickerCandidate[]];
}>();

const columns: UiDataTableColumn[] = [
  { key: 'title', title: '用户', width: 180 },
  { key: 'subtitle', title: '身份摘要' },
];
const open = ref(false);
const keyword = ref('');
const pageNum = ref(1);
const page = ref<{
  records: UserPickerCandidate[];
  total: number;
  navigation?: {
    showTenantNavigation: boolean;
    tenants: UserPickerNavigationItem[];
    organizations: UserPickerNavigationItem[];
    departments: UserPickerNavigationItem[];
  };
}>({ records: [], total: 0 });
const navigationScope = ref<UserPickerNavigationScope>({});
const loading = ref(false);
const error = ref<string>();
const draftIds = ref<UserAccountId[]>([]);
const candidatesById = ref(new Map<UserAccountId, UserPickerCandidate>());
let pageRequestVersion = 0;
let resolveRequestVersion = 0;

const externalIds = computed(() => normalizeUserAccountIds(props.value, props.multiple));
const pageCount = computed(() => Math.max(1, Math.ceil(page.value.total / props.pageSize)));
const rows = computed(() => page.value.records as unknown as UiDataTableRecord[]);
const showNavigation = computed(() => Boolean(page.value.navigation));
const navigationColumns = computed(() => {
  const navigation = page.value.navigation;
  if (!navigation) return [];
  return [
    ...(navigation.showTenantNavigation
      ? [{ key: 'tenant' as const, title: '租户', items: navigation.tenants }]
      : []),
    {
      key: 'organization' as const,
      title: '机构',
      items: navigation.organizations.filter(
        (item) => !navigationScope.value.tenantId || item.tenantId === navigationScope.value.tenantId,
      ),
    },
    {
      key: 'department' as const,
      title: '部门',
      items: navigation.departments.filter(
        (item) =>
          (!navigationScope.value.tenantId || item.tenantId === navigationScope.value.tenantId) &&
          (!navigationScope.value.organizationId ||
            item.organizationId === navigationScope.value.organizationId),
      ),
    },
  ];
});
const selectedUsers = computed(() =>
  draftIds.value.map((id) => candidatesById.value.get(id) ?? { id, title: id, unavailable: true }),
);
const summary = computed(() => {
  if (!externalIds.value.length) return '';
  if (props.multiple) return `已选择 ${externalIds.value.length} 位${props.selectionNoun}`;
  return candidatesById.value.get(externalIds.value[0]!)?.title ?? externalIds.value[0]!;
});
const pickerTags = computed(() =>
  selectedUsers.value.map((user) => ({
    key: user.id,
    label: user.unavailable ? `${user.title}（不可用）` : user.title,
  })),
);
const selection = computed<UiDataTableSelection | undefined>(() =>
  props.multiple
    ? {
        selectedRowKeys: draftIds.value,
        preserveSelectedRowKeys: true,
        disabledOf: (record) => !canSelect(String(record.id)),
        onChange: (keys) => updateDraft(keys.map(String)),
      }
    : undefined,
);

watch(
  externalIds,
  (ids) => {
    void resolveSelection(ids);
    if (open.value) draftIds.value = [...ids];
  },
  { immediate: true },
);

function remember(users: readonly UserPickerCandidate[]) {
  if (!users.length) return;
  const next = new Map(candidatesById.value);
  for (const user of users) next.set(user.id, user);
  candidatesById.value = next;
}

async function resolveSelection(ids: readonly UserAccountId[]) {
  const requestVersion = ++resolveRequestVersion;
  if (!ids.length) return;
  try {
    const users = await props.resolveUsers([...ids]);
    if (requestVersion !== resolveRequestVersion) return;
    remember(users);
  } catch {
    // The caller owns authorization and error presentation for a historical/unavailable projection.
    // Keep the persisted ID visible instead of silently dropping it.
  }
}

function openPicker() {
  if (props.disabled) return;
  draftIds.value = [...externalIds.value];
  pageNum.value = 1;
  navigationScope.value = {};
  error.value = undefined;
  open.value = true;
  void loadPage();
}

function closePicker() {
  open.value = false;
  keyword.value = '';
  pageRequestVersion += 1;
  error.value = undefined;
}

async function loadPage() {
  if (!open.value) return;
  const requestVersion = ++pageRequestVersion;
  loading.value = true;
  error.value = undefined;
  try {
    const result = await props.searchPage({
      keyword: keyword.value.trim(),
      pageNum: pageNum.value,
      pageSize: props.pageSize,
      ...(Object.keys(navigationScope.value).length ? { scope: { ...navigationScope.value } } : {}),
    });
    if (requestVersion !== pageRequestVersion || !open.value) return;
    page.value = result;
    remember(result.records);
  } catch (cause) {
    if (requestVersion !== pageRequestVersion || !open.value) return;
    error.value = cause instanceof Error ? cause.message : '用户候选加载失败';
  } finally {
    if (requestVersion === pageRequestVersion) loading.value = false;
  }
}

function clearSelection() {
  draftIds.value = [];
  emit('update:value', userPickerValue([], props.multiple));
  emit('select', []);
}

function openWithKeyword(value: string) {
  keyword.value = value;
  openPicker();
}

function selectNavigation(level: 'tenant' | 'organization' | 'department', item?: UserPickerNavigationItem) {
  if (level === 'tenant') {
    navigationScope.value = item ? { tenantId: item.id } : {};
  } else if (level === 'organization') {
    navigationScope.value = item
      ? { tenantId: item.tenantId ?? navigationScope.value.tenantId, organizationId: item.id }
      : navigationScope.value.tenantId
        ? { tenantId: navigationScope.value.tenantId }
        : {};
  } else {
    navigationScope.value = item
      ? {
          tenantId: item.tenantId ?? navigationScope.value.tenantId,
          organizationId: item.organizationId ?? navigationScope.value.organizationId,
          departmentId: item.id,
        }
      : navigationScope.value.organizationId
        ? { tenantId: navigationScope.value.tenantId, organizationId: navigationScope.value.organizationId }
        : navigationScope.value.tenantId
          ? { tenantId: navigationScope.value.tenantId }
          : {};
  }
  pageNum.value = 1;
  void loadPage();
}

function isNavigationSelected(
  level: 'tenant' | 'organization' | 'department',
  item: UserPickerNavigationItem,
) {
  return navigationScope.value[`${level}Id`] === item.id;
}

function searchInDialog(value: string) {
  keyword.value = value;
  pageNum.value = 1;
  void loadPage();
}

function changePage(nextPage: number) {
  if (nextPage < 1 || nextPage > pageCount.value || nextPage === pageNum.value) return;
  pageNum.value = nextPage;
  void loadPage();
}

function canSelect(id: UserAccountId) {
  const candidate = candidatesById.value.get(id);
  if (candidate?.disabled || candidate?.unavailable) return false;
  return (
    draftIds.value.includes(id) ||
    props.maxSelection === undefined ||
    draftIds.value.length < props.maxSelection
  );
}

function updateDraft(ids: UserAccountId[]) {
  const next = normalizeUserAccountIds(ids, props.multiple).filter(canSelect);
  draftIds.value = props.maxSelection === undefined ? next : next.slice(0, props.maxSelection);
}

function selectSingle(record: UiDataTableRecord) {
  if (props.multiple || !canSelect(String(record.id))) return;
  draftIds.value = [String(record.id)];
}

function completeSingle(record: UiDataTableRecord) {
  if (props.multiple || !canSelect(String(record.id))) return;
  const id = String(record.id);
  draftIds.value = [id];
  emit('update:value', id);
  emit('select', [candidatesById.value.get(id) ?? { id, title: id, unavailable: true }]);
  closePicker();
}

function confirm() {
  const users = selectedUsers.value;
  emit('update:value', userPickerValue(draftIds.value, props.multiple));
  emit('select', users);
  closePicker();
}
</script>

<template>
  <div class="user-picker">
    <ObjectPickerInput
      :value="summary"
      :placeholder="placeholder"
      :disabled="disabled"
      :browse-label="title"
      @browse="openWithKeyword"
      @clear="clearSelection"
    />

    <UiModal
      :open="open"
      :title="title"
      :width="showNavigation ? 1120 : 760"
      :confirm-disabled="loading"
      :closable="!loading"
      @confirm="confirm"
      @cancel="closePicker"
    >
      <div class="user-picker-dialog">
        <UiSearchInput
          :value="keyword"
          :placeholder="searchPlaceholder"
          :loading="loading"
          search-text="搜索"
          @update:value="keyword = $event"
          @search="searchInDialog"
        />
        <div v-if="multiple" class="user-picker-selection-summary">
          <UiTagList :items="pickerTags" :empty-text="'尚未选择'" />
          <span>已选 {{ draftIds.length }} 位{{ selectionNoun }}</span>
          <UiButton v-if="draftIds.length" type="link" size="small" @click="draftIds = []">清空选择</UiButton>
        </div>
        <div class="user-picker-browse">
          <aside v-if="showNavigation" class="user-picker-navigation" aria-label="人员范围导航">
            <RecordExplorerPanel
              v-for="column in navigationColumns"
              :key="column.key"
              class="user-picker-navigation-column"
              :title="column.title"
              embedded
              :refreshable="false"
              :searchable="false"
              :collapse-action="false"
            >
              <div class="user-picker-navigation-items">
                <UiRecordExplorerItem
                  :title="`全部${column.title}`"
                  clickable
                  :selected="!navigationScope[`${column.key}Id`]"
                  @click="selectNavigation(column.key)"
                />
                <UiRecordExplorerItem
                  v-for="item in column.items"
                  :key="item.id"
                  :title="item.title"
                  clickable
                  :selected="isNavigationSelected(column.key, item)"
                  @click="selectNavigation(column.key, item)"
                />
                <UiRecordExplorerItem
                  v-if="column.items.length === 0"
                  title="当前范围没有可选项"
                  muted
                />
              </div>
            </RecordExplorerPanel>
          </aside>
          <div class="user-picker-results">
            <div v-if="error" class="user-picker-error">
              <UiError :message="error" />
              <UiButton size="small" @click="loadPage">重试</UiButton>
            </div>
            <UiDataTable
              :columns="columns"
              :rows="rows"
              :loading="loading"
              :selection="selection"
              :selected-row-key="multiple ? undefined : draftIds[0]"
              :clickable-rows="!multiple"
              :empty-description="emptyDescription"
              @row-click="selectSingle"
              @row-dblclick="completeSingle"
            />
            <footer class="user-picker-pagination">
              <span>共 {{ page.total }} 位{{ selectionNoun }}</span>
              <span>第 {{ pageNum }} / {{ pageCount }} 页</span>
              <UiButton
                icon-name="left"
                aria-label="上一页"
                :disabled="loading || pageNum <= 1"
                @click="changePage(pageNum - 1)"
              />
              <UiButton
                icon-name="right"
                aria-label="下一页"
                :disabled="loading || pageNum >= pageCount"
                @click="changePage(pageNum + 1)"
              />
            </footer>
          </div>
        </div>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.user-picker {
  min-width: 0;
}

.user-picker-dialog {
  display: grid;
  gap: 12px;
}

.user-picker-browse {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  min-height: 340px;
}

.user-picker-browse:has(.user-picker-navigation) {
  grid-template-columns: minmax(460px, 3fr) minmax(0, 7fr);
  gap: 16px;
}

.user-picker-navigation {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--muyun-border);
  border-radius: 6px;
  background: var(--muyun-hover-subtle);
}

.user-picker-navigation-column {
  min-width: 0;
  max-height: 410px;
  padding: 8px 6px;
  border-right: 1px solid var(--muyun-border);
}

.user-picker-navigation-column:last-child {
  border-right: 0;
}

.user-picker-navigation-column :deep(.record-explorer-panel-header) {
  margin-bottom: 6px;
  padding: 0 4px;
}

.user-picker-navigation-column :deep(.management-panel-header-title) {
  font-size: 13px;
}

.user-picker-navigation-items {
  display: grid;
  align-content: start;
  gap: 2px;
  min-height: 0;
  overflow-y: auto;
}

.user-picker-results {
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
}

.user-picker-error {
  display: grid;
  justify-items: start;
  gap: 6px;
}

.user-picker-selection-summary,
.user-picker-pagination {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.user-picker-selection-summary > :first-child {
  min-width: 0;
  flex: 1 1 auto;
}

.user-picker-selection-summary > span,
.user-picker-pagination > span {
  color: var(--muyun-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.user-picker-pagination {
  justify-content: flex-end;
}
</style>
