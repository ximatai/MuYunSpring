<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementPanelHeader,
  ManagementWorkspace,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordExplorerPanel,
  presentPlatformError,
} from '@muyun/platform-components';
import { useWorkspaceViewUnsavedState } from '@muyun/platform-workbench';
import {
  createStaticResourceCrudClient,
  pageActionEntryDescription,
  pageActionEntryTitle,
  normalizeError,
  platformErrorCodes,
  useModuleContext,
  type ModuleCrudClient,
  type ModuleRuntimeAction,
} from '@muyun/web-core';
import {
  confirmAction,
  UiButton,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSpin,
  UiSwitch,
  UiRadioGroup,
  UiTree,
  type UiRadioOption,
  type UiRecordInlineAction,
  type UiTreeLoadRequest,
  type UiTreeLoadResult,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import type {
  MetadataField,
  ModuleMetadataRelation,
  ResolvedModuleUiDescriptor,
  WebPageResponse,
  WebQueryCondition,
} from '@muyun/web-contracts';
import {
  createPageCompositionDraftState,
  type PageComposerField,
  type PageComposerFormItem,
  type PageComposerFieldProperties,
  type PageComposerSlot,
  type PageQuerySummary,
  type PageQuerySummarySource,
  defaultPageQuerySummaryLabel,
  hasDefaultPageQuerySummaryLabel,
  pageQuerySummaryDescription,
} from './pageCompositionDraftState';
import {
  canPlaceActionInAnchor,
  modeAwareTree,
  type CompositionMode,
  type CompositionSkeleton,
  type PageCompositionActionPlacement,
  defaultPageActionEntries,
  withStandardActionEntries,
  actionButtonMembers,
  actionButtons,
} from './pageCompositionMode';
import { pageCompositionTransport } from './pageCompositionTransport';
import PageCompositionDescriptorPreview from './PageCompositionDescriptorPreview.vue';
import PageQuerySummaryEditor, { type PageQuerySummaryEditorIssues } from './PageQuerySummaryEditor.vue';
import PageCompositionTree, { type ComposerDropTarget } from './PageCompositionTree.vue';
import {
  PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
  parsePageCompositionDragPayload,
  parseMetadataDragPayload,
  type PageCompositionDragPayload,
} from './pageCompositionDragPayload';

import {
  resolveCompositionPlacement,
  type PageCompositionStructure,
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
} from './pageCompositionPlacement';

defineOptions({ name: 'PageCompositionWorkspace' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const state = createPageCompositionDraftState();

// Governance tabs retain drafts through KeepAlive. Re-entering the composer
// refreshes its source catalogue, not the user's unsaved page composition.
let activatedOnce = false;
onActivated(() => {
  if (activatedOnce && !isMutating.value) void loadMetadataTree();
  activatedOnce = true;
});
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const relation = ref<ModuleMetadataRelation>();
const metadataRelations = ref<ModuleMetadataRelation[]>([]);
const metadataFields = ref<PageComposerField[]>([]);
const referenceFieldDirectories = ref(new Map<string, PageComposerField[]>());
const referenceDirectoryRequests = new Map<string, Promise<PageComposerField[]>>();
const metadataTreeReloadKey = ref(0);
let referenceDirectoryEpoch = 0;
let hydrateSequence = 0;
const childMetadataFields = ref(new Map<string, PageComposerField[]>());
const page = ref<PageDefinition>();
const variant = ref<PresentationVariant>();
const revision = ref<PresentationRevision>();
const publishedRevision = ref<PresentationRevision>();
const skeletons = ref<CompositionSkeleton[]>([]);
const compositionMode = ref<CompositionMode>();
const configuredMode = ref<CompositionMode>();
const explorerTitleField = ref('title');
const quickSearchFields = ref<string[]>([]);
const searchableFields = ref<string[]>([]);
const explorerSecondaryField = ref<string>();
const moduleActions = ref<ModuleRuntimeAction[]>([]);
const actionPlacements = ref<PageCompositionActionPlacement[]>([]);
const actionFormMode = ref<'create' | 'edit'>('edit');
const selectedActionKey = ref<string>();
const selectedActionEntry = computed(() =>
  actionPlacements.value.find(
    (entry) => `ui:action:${entry.anchor}:${entry.actionCode}` === selectedActionKey.value,
  ),
);
const actionIssues = computed(() =>
  actionPlacements.value
    .filter(
      (entry) =>
        !moduleActions.value.some(
          (action) => action.actionCode === entry.actionCode && canPlaceActionInAnchor(action, entry.anchor),
        ),
    )
    .map((entry) => `${entry.title ?? entry.actionCode}：来源失效或此区域尚无执行契约`),
);

const editorMode = ref<'fields' | 'actions'>('fields');
const editorModeOptions: UiRadioOption[] = [
  { value: 'fields', label: '字段模式' },
  { value: 'actions', label: '动作模式' },
];
const skeleton = computed(() => skeletons.value.find((item) => item.mode === compositionMode.value));
const fieldKeyword = ref('');
const showSystemFields = ref(false);
const selectedMetadataTreeKey = ref<string>();
const metadataExpandedKeys = ref<string[]>(['metadata:root']);
const propertyDrawerOpen = ref(false);
const propertyDraft = ref<PageComposerFieldProperties>({});
const groupTitleDraft = ref('');
const groupSubtitleDraft = ref('');
const quickSearchPlaceholderDraft = ref('');
const savedUiTreeJson = ref<string>();
const previewDescriptor = ref<ResolvedModuleUiDescriptor>();
const previewStructure = ref<PageCompositionStructure>();
const previewLoading = ref(false);
const previewError = ref<string>();
interface PlatformFieldPolicy {
  fieldName: string;
  composable: boolean;
  readOnly: boolean;
  referenceModuleAlias?: string;
}
interface PageReferenceField {
  id: string;
  name: string;
  label: string;
  valueType?: string;
  referenceModuleAlias?: string;
  referenceCardinality?: 'ONE' | 'MANY';
  expandable?: boolean;
  readOnly?: boolean;
  systemManaged?: boolean;
}
interface PageQuerySummaryCatalog {
  moduleAlias: string;
  fields?: Array<{ fieldName: string; title: string }>;
  contributors?: Array<{ contributorKey: string; title: string }>;
  groupFields?: Array<{ fieldName: string; title: string; kind: 'OPTION' | 'REFERENCE' }>;
}
const platformFieldPolicies = ref(new Map<string, PlatformFieldPolicy>());
const summaryCatalog = ref<PageQuerySummaryCatalog>();
const summaryCatalogLoading = ref(false);
const summaryCatalogError = ref<string>();
const summaryDrawerOpen = ref(false);
const selectedSummaryKey = ref<string>();
const summaryFocusRequest = ref(0);
const customSummaryKeys = new Set<string>();
let summaryCatalogSequence = 0;
let previewRequestSequence = 0;
let previewDebounceTimer: ReturnType<typeof setTimeout> | undefined;
let workspaceLoadSequence = 0;
let metadataLoadSequence = 0;
const draftParseError = ref<string>();
const draftConflict = ref(false);
const compositionLoading = ref(false);
let compositionLoadSequence = 0;

const hasListPreview = computed(() => previewDescriptor.value?.page?.template === 'LIST_DETAIL_CARD');
const supportsQuerySummaries = computed(() => skeleton.value?.mode === 'LIST_CARD');
const summarySources = computed(() => state.querySummaries.value);
const querySummaryIssuesByKey = computed<Record<string, PageQuerySummaryEditorIssues>>(() => {
  const issues: Record<string, PageQuerySummaryEditorIssues> = {};
  for (const summary of summarySources.value) {
    const item: PageQuerySummaryEditorIssues = {};
    if (!['MATCHED_COUNT', 'SUM', 'CONTRIBUTOR', 'GROUPED'].includes(summary.source))
      item.source = '请选择有效的统计方式。';
    if (!summary.label.trim()) item.label = '请填写展示名称。';
    if (summary.source === 'SUM' && !summary.fieldName) item.fieldName = '请选择数值字段。';
    if (summary.source === 'CONTRIBUTOR' && !summary.contributorKey) item.contributorKey = '请选择业务指标。';
    // GROUPED always includes each group's record count; its numeric sum remains optional.
    if (summary.source === 'GROUPED' && !summary.groupByField) item.groupByField = '请选择分组字段。';
    if (Object.keys(item).length) issues[summary.key] = item;
  }
  return issues;
});
const summaryEditorIssues = computed<Record<string, PageQuerySummaryEditorIssues>>(() => {
  const issues = { ...querySummaryIssuesByKey.value };
  const fields = new Set(summaryCatalog.value?.fields?.map((field) => field.fieldName) ?? []);
  const contributors = new Set(summaryCatalog.value?.contributors?.map((item) => item.contributorKey) ?? []);
  const groupFields = new Set(summaryCatalog.value?.groupFields?.map((field) => field.fieldName) ?? []);
  if (!summaryCatalog.value || summaryCatalogLoading.value || summaryCatalogError.value) return issues;
  for (const summary of summarySources.value) {
    const item = { ...(issues[summary.key] ?? {}) };
    if (
      (summary.source === 'SUM' || summary.source === 'GROUPED') &&
      summary.fieldName &&
      !fields.has(summary.fieldName)
    )
      item.fieldName = '该数值字段已不可用，请重新选择。';
    if (
      summary.source === 'CONTRIBUTOR' &&
      summary.contributorKey &&
      !contributors.has(summary.contributorKey)
    )
      item.contributorKey = '该业务指标已不可用，请重新选择。';
    if (summary.source === 'GROUPED' && summary.groupByField && !groupFields.has(summary.groupByField))
      item.groupByField = '该分组字段已不可用，请重新选择。';
    if (Object.keys(item).length) issues[summary.key] = item;
  }
  return issues;
});
const summaryIssueEntries = computed(() =>
  summarySources.value.flatMap((summary) => {
    const messages = Object.values(summaryEditorIssues.value[summary.key] ?? {});
    return messages.length ? [{ key: summary.key, title: summary.label || summary.key, messages }] : [];
  }),
);
const hasSummaryIssues = computed(() => summaryIssueEntries.value.length > 0);
const summaryDescriptions = computed(() =>
  Object.fromEntries(
    summarySources.value.map((summary) => [
      summary.key,
      pageQuerySummaryDescription(summary, summaryCatalog.value),
    ]),
  ),
);
const summaryTreeIssues = computed(() =>
  Object.fromEntries(
    summarySources.value
      .filter((summary) => Object.keys(summaryEditorIssues.value[summary.key] ?? {}).length)
      .map((summary) => [summary.key, '配置未完成或来源失效']),
  ),
);
const effectivePreviewMode = computed(() => {
  const mode = state.previewMode.value;
  if (mode === 'detail' || mode === 'edit') return mode;
  return hasListPreview.value ? 'list' : 'detail';
});
const previewModes = computed<UiRadioOption[]>(() => [
  ...(hasListPreview.value ? [{ value: 'list', label: '列表' }] : []),
  { value: 'detail', label: '页面' },
  { value: 'edit', label: '表单' },
]);
const removedDraft = ref<{ before: string; after: string }>();
function validRelationColumnWidth(width: string) {
  return /^[1-9]\d*px$/.test(width) && Number.parseInt(width, 10) <= 2_147_483_647;
}
const propertyIssues = computed(() => [
  ...state.listFields.value.filter((field) => {
    const width = field.properties?.width?.trim();
    return width && !/^\d+(px|%)$/.test(width);
  }),
  ...state.formRelations.value
    .flatMap((relation) => relation.fields)
    .filter((field) => {
      const width = field.properties?.width?.trim();
      return width && !validRelationColumnWidth(width);
    }),
]);
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase();
  const fields = filterSystemFields(metadataFields.value);
  if (!keyword) return fields;
  return fields.filter(
    (field) =>
      field.title.toLowerCase().includes(keyword) ||
      field.fieldName.toLowerCase().includes(keyword) ||
      // Keep an already loaded matching descendant reachable; search never expands unloaded directories.
      [...referenceFieldDirectories.value.entries()]
        .filter(([key]) => key.startsWith(`${props.moduleAlias}:${field.fieldName}`))
        .flatMap(([, children]) => children)
        .some(
          (child) =>
            child.title.toLowerCase().includes(keyword) || child.fieldName.toLowerCase().includes(keyword),
        ),
  );
});
const allMetadataFields = computed(() => [
  ...metadataFields.value,
  ...[...referenceFieldDirectories.value.entries()]
    .filter(([key]) => key.startsWith(`${props.moduleAlias}:`) && key !== `${props.moduleAlias}:`)
    .flatMap(([, fields]) => fields),
]);
const selectedField = computed(
  () => state.selectedNode.value?.field ?? state.selectedNode.value?.relationField,
);
const selectedRelation = computed(() => state.selectedNode.value?.relation);
const selectedRelationField = computed(() => state.selectedNode.value?.relationField);
const selectedGroup = computed(() => state.selectedNode.value?.group);
const selectedQuickSearch = computed(() => state.selectedNode.value?.id === 'template:list:quick-search');
const selectedFieldLabel = computed(() =>
  selectedQuickSearch.value
    ? '快速查询'
    : selectedField.value
      ? fieldDisplayTitle(selectedField.value)
      : selectedRelationField.value
        ? fieldDisplayTitle(selectedRelationField.value)
        : selectedGroup.value
          ? selectedGroup.value.title
          : (selectedRelation.value?.title ?? '组件'),
);
const propertyDrawerTitle = computed(() =>
  selectedActionEntry.value
    ? `配置：${pageActionEntryTitle(selectedActionEntry.value)}`
    : selectedQuickSearch.value
      ? '配置：快速查询占位提示'
      : `配置：${selectedFieldLabel.value}`,
);
const selectedPreviewFieldName = computed(() => {
  const node = state.selectedNode.value;
  return node?.field ? `${node.slot}:${node.field.fieldName}` : undefined;
});
const currentUiTreeJson = computed(() =>
  JSON.stringify(
    skeleton.value
      ? modeAwareTree(
          state.toManagementUiTree(),
          skeleton.value,
          { titleField: explorerTitleField.value, secondaryField: explorerSecondaryField.value },
          quickSearchFields.value,
          actionPlacements.value,
        )
      : state.toManagementUiTree(),
  ),
);
const hasUnsavedChanges = computed(() =>
  Boolean(revision.value?.id && savedUiTreeJson.value !== currentUiTreeJson.value),
);
useWorkspaceViewUnsavedState('页面配置', () => hasUnsavedChanges.value);
const isMutating = computed(
  () => saving.value || publishing.value || loading.value || compositionLoading.value,
);
const unavailableNavigationSources = computed(() => {
  const known = new Set(metadataFields.value.map((field) => field.fieldName));
  const fields = [...quickSearchFields.value];
  if (skeleton.value?.columns === false) {
    fields.push(explorerTitleField.value || '导航标题（未配置）');
    if (explorerSecondaryField.value) fields.push(explorerSecondaryField.value);
  }
  return [...new Set(fields.filter((field) => !known.has(field)))];
});
const unavailableSources = computed(() =>
  [
    ...(skeleton.value?.columns === false ? [] : state.listFields.value),
    ...state.formFields.value,
    ...state.formGroups.value.flatMap((group) => group.fields),
    ...state.formRelations.value.flatMap((relation) => relation.fields),
  ]
    .filter((field) => field.unavailable)
    .map((field) => field.fieldName)
    .concat(
      state.formRelations.value
        .filter((relation) => relation.unavailable)
        .map((relation) => relation.relationCode),
      unavailableNavigationSources.value,
    ),
);
const propertyValidationMessage = computed(() => {
  if (state.selectedNode.value?.slot !== 'list' && !selectedRelationField.value) return undefined;
  const width = propertyDraft.value.width?.trim();
  if (!width || (selectedRelationField.value ? validRelationColumnWidth(width) : /^\d+(px|%)$/.test(width)))
    return undefined;
  if (selectedRelationField.value) return '子表列宽请输入正整数，例如 160。';
  return '列宽需使用数字加 px 或 %，例如 160px、25%。';
});
const selectedUiTreeKey = computed(() => {
  if (selectedSummaryKey.value) return `ui:summary:${selectedSummaryKey.value}`;
  if (selectedActionKey.value) return selectedActionKey.value;
  const node = state.selectedNode.value;
  if (!node) return undefined;
  if (node.kind === 'template') return 'ui:template:list:quick-search';
  if (node.kind === 'relation') return `ui:relation:form:${node.relation?.id}`;
  if (node.kind === 'relationField')
    return `ui:relation-field:form:${node.relation?.id}:${node.relationField?.id}`;
  if (node.kind === 'group') return `ui:group:form:${node.group?.id}`;
  if (node.kind === 'groupField') return `ui:group-field:form:${node.group?.id}:${node.field?.id}`;
  return node.kind === 'slot'
    ? node.slot === 'list'
      ? 'ui:slot:list:fields'
      : `ui:slot:${node.slot}`
    : `ui:field:${node.slot}:${node.field?.id}`;
});
const composerTitle = computed(() => page.value?.title ?? props.moduleTitle ?? '页面编排');
const paletteTitle = computed(() => (editorMode.value === 'fields' ? '可用字段' : '模块动作'));
const structureTitle = computed(() => (editorMode.value === 'fields' ? '页面结构' : '动作结构'));
const mainEntityTitle = computed(
  () => relation.value?.title ?? props.moduleTitle ?? relation.value?.relationAlias ?? '主实体',
);
const compositionSubtitle = computed(() => {
  if (!page.value) return '尚未初始化页面定义';
  return `Web · 全局 · ${revision.value ? `草稿 v${revision.value.revisionNo}` : '尚无可编辑草稿'} · 最近发布 ${publishedRevision.value ? `v${publishedRevision.value.revisionNo}` : '无'}`;
});
const metadataTreeNodes = computed<UiTreeNode[]>(() => [
  ...(editorMode.value === 'fields'
    ? [
        {
          key: 'metadata:root',
          title: mainEntityTitle.value,
          secondary: '主元数据',
          children: [
            ...visibleFields.value.map(metadataFieldNode),
            ...childRelationNodes(relation.value?.metadataId),
          ],
        },
      ]
    : []),
  ...(editorMode.value === 'actions'
    ? moduleActions.value.map((action) => ({
        key: `module-action:${action.actionCode}`,
        title: action.title ?? action.actionCode,
        tag: action.category === 'STANDARD' ? '平台托管' : undefined,
        muted: !canDragPaletteAction(action),
        isLeaf: true,
      }))
    : []),
]);
watch(editorMode, () => {
  selectedActionKey.value = undefined;
  propertyDrawerOpen.value = false;
  selectedMetadataTreeKey.value = undefined;
});
watch(showSystemFields, () => {
  // Managed UiTree caches rendered children. Rebuild those branches with the current visibility rule.
  metadataTreeReloadKey.value += 1;
});
watch(state.selectedNodeId, () => {
  propertyDraft.value = { ...(selectedField.value?.properties ?? {}) };
  quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
  groupTitleDraft.value = selectedGroup.value?.title ?? '';
  groupSubtitleDraft.value = selectedGroup.value?.subtitle ?? '';
});
watch(currentUiTreeJson, (json) => {
  if (removedDraft.value && removedDraft.value.after !== json) removedDraft.value = undefined;
});

// Older transient drag sessions could place one form field in more than one group.  The editor
// repairs that impossible state before previewing, keeping the UI tree and server descriptor aligned.
watch([state.formFields, state.formGroups], () => state.normalizeFormFieldPlacements(), { immediate: true });

watch(
  () => props.moduleAlias,
  () => {
    resetPreviewDescriptor();
    relation.value = undefined;
    metadataRelations.value = [];
    metadataFields.value = [];
    referenceFieldDirectories.value = new Map();
    referenceDirectoryRequests.clear();
    summaryCatalogSequence += 1;
    summaryCatalog.value = undefined;
    summaryCatalogError.value = undefined;
    summaryCatalogLoading.value = false;
    summaryDrawerOpen.value = false;
    selectedSummaryKey.value = undefined;
    customSummaryKeys.clear();
    state.replaceQuerySummaries([]);
    metadataTreeReloadKey.value += 1;
    childMetadataFields.value = new Map();
    revision.value = undefined;
    variant.value = undefined;
    draftParseError.value = undefined;
    draftConflict.value = false;
    removedDraft.value = undefined;
    saving.value = false;
    publishing.value = false;
    compositionLoading.value = false;
    propertyDrawerOpen.value = false;
    page.value = undefined;
    publishedRevision.value = undefined;
    quickSearchFields.value = [];
    moduleActions.value = [];
    actionPlacements.value = [];
    explorerTitleField.value = 'title';
    explorerSecondaryField.value = undefined;
    state.replaceFields({ list: [], form: [] });
    state.updateQuickSearchPlaceholder(undefined);
    savedUiTreeJson.value = undefined;
    void loadWorkspace();
  },
  { immediate: true },
);

watch([currentUiTreeJson, unavailableSources, () => variant.value?.id, () => revision.value?.id], () =>
  schedulePreviewDescriptor(),
);

onBeforeUnmount(() => {
  workspaceLoadSequence += 1;
  resetPreviewDescriptor();
});

type PageDefinition = {
  id?: string;
  version?: number;
  title?: string;
  alias?: string;
  moduleAlias?: string;
  contractType?: 'management' | 'form' | 'detail' | 'reference';
  mainRelationId?: string;
  enabled?: boolean;
};
type PresentationVariant = {
  id?: string;
  version?: number;
  title?: string;
  pageId?: string;
  clientType?: 'web' | 'mobile';
  scopeType?: 'global' | 'tenant' | 'organization';
  enabled?: boolean;
};
type PresentationRevision = {
  id?: string;
  version?: number;
  title?: string;
  variantId?: string;
  revisionNo?: number;
  templateAlias?: string;
  templateVersion?: number;
  uiTreeJson?: string;
  status?: 'draft' | 'published' | 'archived';
  enabled?: boolean;
};
type PresentationRevisionPreview = {
  pageId: string;
  variantId: string;
  revisionId: string;
  uiDescriptor: ResolvedModuleUiDescriptor;
};
function pageClient(moduleAlias = props.moduleAlias) {
  return createStaticResourceCrudClient<PageDefinition>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(moduleAlias)}/pages`,
  );
}

function variantClient(moduleAlias: string, pageId: string) {
  return createStaticResourceCrudClient<PresentationVariant>(
    moduleContext.http,
    `/platform.module/${encodeURIComponent(moduleAlias)}/pages/${encodeURIComponent(pageId)}/presentation-variants`,
  );
}

function revisionClient(variantId: string) {
  return createStaticResourceCrudClient<PresentationRevision>(
    moduleContext.http,
    `/platform.presentation-variant/${encodeURIComponent(variantId)}/revisions`,
  );
}

async function applyConfiguredMode() {
  if (!configuredMode.value || isMutating.value) return;
  const accepted = await confirmAction({
    title: '切换页面骨架',
    content:
      '详情、表单和子表配置会保留。导航区域将按新模式重建，原列表列不会用于树或微列表。保存并发布后才会替换线上页面。是否继续？',
  });
  if (!accepted) return;
  compositionMode.value = configuredMode.value;
  explorerTitleField.value = metadataFields.value.some((field) => field.fieldName === 'title')
    ? 'title'
    : (metadataFields.value[0]?.fieldName ?? '');
  explorerSecondaryField.value = undefined;
}

async function loadWorkspace() {
  const requestSequence = ++workspaceLoadSequence;
  const moduleAlias = props.moduleAlias;
  if (!(await loadMetadataTree(requestSequence, moduleAlias))) return;
  if (requestSequence !== workspaceLoadSequence) return;
  compositionMode.value = configuredMode.value;
  await loadComposition(requestSequence, moduleAlias);
}

async function loadMetadataTree(requestSequence = workspaceLoadSequence, moduleAlias = props.moduleAlias) {
  const metadataSequence = ++metadataLoadSequence;
  referenceDirectoryEpoch += 1;
  referenceDirectoryRequests.clear();
  referenceFieldDirectories.value = new Map();
  metadataTreeReloadKey.value += 1;
  const current = () =>
    requestSequence === workspaceLoadSequence && metadataSequence === metadataLoadSequence;
  loading.value = true;
  try {
    const [profile, runtime] = await Promise.all([
      moduleContext.http.request<{
        overviewMode: CompositionMode;
        compositionSkeletons: CompositionSkeleton[];
        searchableFields?: string[];
        platformFieldPolicies: PlatformFieldPolicy[];
      }>({ method: 'GET', path: `/platform.module/${encodeURIComponent(moduleAlias)}/overview-mode` }),
      Promise.resolve()
        .then(() =>
          moduleContext.http.request<{ actions?: ModuleRuntimeAction[] }>({
            method: 'GET',
            path: `/platform.module/${encodeURIComponent(moduleAlias)}/context`,
          }),
        )
        .catch(() => undefined),
    ]);
    if (!current()) return false;
    if (!profile.compositionSkeletons?.length)
      throw new Error('服务端尚未提供页面骨架，请重启后端后重新加载');
    const relations = await loadAll<ModuleMetadataRelation>(
      `/platform.module/${encodeURIComponent(moduleAlias)}/metadata-relations/query`,
    );
    if (!current()) return;
    const main = relations.find((item) => item.relationRole === 'main' || item.relationRole === 'MAIN');
    if (!profile.platformFieldPolicies) throw new Error('服务端尚未提供平台字段策略，请重启后端后重新加载');
    platformFieldPolicies.value = new Map(
      profile.platformFieldPolicies.map((policy) => [policy.fieldName, policy]),
    );
    const toFields = (fields: MetadataField[]) =>
      fields
        .filter((field) => field.enabled !== false && !isRuntimeReservedMetadataField(field))
        .map(toComposerField)
        .filter((field): field is PageComposerField => field != null);
    const fields = main?.metadataId
      ? await loadAll<MetadataField>(`/platform.metadata/${encodeURIComponent(main.metadataId)}/fields/query`)
      : [];
    if (!current()) return;
    const directChildren = relations.filter(
      (candidate) =>
        main?.metadataId && candidate.parentMetadataId === main.metadataId && Boolean(candidate.metadataId),
    );
    const childFieldEntries = await Promise.all(
      directChildren.map(
        async (child) =>
          [
            child.id ?? child.metadataId!,
            toFields(
              await loadAll<MetadataField>(
                `/platform.metadata/${encodeURIComponent(child.metadataId!)}/fields/query`,
              ),
            ),
          ] as const,
      ),
    );
    if (!current()) return;
    // Install a complete catalogue together. Refresh never empties the navigator or loses local edits.
    const treeJson = currentUiTreeJson.value;
    const selected = state.selectedNodeId.value;
    skeletons.value = profile.compositionSkeletons;
    configuredMode.value = profile.overviewMode.toUpperCase() as CompositionMode;
    searchableFields.value = profile.searchableFields ?? [];
    if (runtime) moduleActions.value = runtime.actions ?? [];
    relation.value = main;
    metadataRelations.value = relations;
    const fallbackFields = toFields(fields);
    // Preserve metadata identities and field policies while adding the directory's reference facts.
    const referenceRoot = await loadReferenceDirectory(moduleAlias, '');
    if (!current()) return;
    const referenceByName = new Map(referenceRoot.map((field) => [field.fieldName, field]));
    metadataFields.value = fallbackFields.map((field) => {
      const reference = referenceByName.get(field.fieldName);
      return reference
        ? {
            ...field,
            referenceModuleAlias: reference.referenceModuleAlias ?? field.referenceModuleAlias,
            referenceCardinality: reference.referenceCardinality ?? field.referenceCardinality,
            expandable: reference.expandable ?? field.expandable,
            platformReadOnly: field.platformReadOnly || reference.platformReadOnly,
          }
        : field;
    });
    childMetadataFields.value = new Map(childFieldEntries);
    if (revision.value && !draftParseError.value) {
      await hydrateDraft({ ...revision.value, uiTreeJson: treeJson }, false);
      if (state.nodes.value.some((node) => node.id === selected)) state.selectedNodeId.value = selected;
    }
    return true;
  } catch (cause) {
    if (current()) presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    if (current()) loading.value = false;
  }
}

function referenceDirectoryKey(moduleAlias: string, path: string) {
  return `${moduleAlias}:${path}`;
}

async function loadSummaryCatalog(retry = false) {
  if (summaryCatalogLoading.value) return;
  if (summaryCatalog.value && !retry) return;
  const sequence = ++summaryCatalogSequence;
  const moduleAlias = props.moduleAlias;
  summaryCatalogLoading.value = true;
  summaryCatalogError.value = undefined;
  try {
    const catalog = await moduleContext.http.request<PageQuerySummaryCatalog>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/page-query-summary-catalog`,
    });
    if (sequence !== summaryCatalogSequence || moduleAlias !== props.moduleAlias) return;
    summaryCatalog.value = {
      moduleAlias,
      fields: catalog.fields ?? [],
      contributors: catalog.contributors ?? [],
      groupFields: catalog.groupFields ?? [],
    };
  } catch (cause) {
    if (sequence !== summaryCatalogSequence || moduleAlias !== props.moduleAlias) return;
    summaryCatalogError.value = cause instanceof Error ? cause.message : '汇总目录加载失败。';
  } finally {
    if (sequence === summaryCatalogSequence) summaryCatalogLoading.value = false;
  }
}

function openSummaryEditor(summaryKey?: string) {
  if (!supportsQuerySummaries.value || isMutating.value) return;
  if (summaryKey && !state.querySummaries.value.some((summary) => summary.key === summaryKey)) return;
  // The two inline drawers share one workspace edge. A summary never opens behind a field editor.
  propertyDrawerOpen.value = false;
  selectedActionKey.value = undefined;
  state.selectedNodeId.value = undefined;
  selectedSummaryKey.value = summaryKey;
  summaryFocusRequest.value += 1;
  summaryDrawerOpen.value = true;
  void loadSummaryCatalog();
}

function closeSummaryEditor() {
  summaryDrawerOpen.value = false;
  selectedSummaryKey.value = undefined;
}

function nextSummaryKey() {
  const keys = new Set(state.querySummaries.value.map((summary) => summary.key));
  let index = 1;
  while (keys.has(`summary_${index}`)) index += 1;
  return `summary_${index}`;
}

function addQuerySummary(source: PageQuerySummarySource) {
  const firstField = summaryCatalog.value?.fields?.[0];
  const firstContributor = summaryCatalog.value?.contributors?.[0];
  const nextSummary: PageQuerySummary = {
    key: nextSummaryKey(),
    label: '',
    source,
    ...(source === 'SUM' && firstField ? { fieldName: firstField.fieldName } : {}),
    ...(source === 'CONTRIBUTOR' && firstContributor
      ? { contributorKey: firstContributor.contributorKey }
      : {}),
    ...(source === 'GROUPED' && summaryCatalog.value?.groupFields?.[0]
      ? { groupByField: summaryCatalog.value.groupFields[0].fieldName }
      : {}),
  };
  state.replaceQuerySummaries([
    ...state.querySummaries.value,
    {
      ...nextSummary,
      label: defaultPageQuerySummaryLabel(nextSummary, summaryCatalog.value),
    },
  ]);
  selectedSummaryKey.value = nextSummary.key;
  summaryFocusRequest.value += 1;
}

function updateQuerySummary(index: number, patch: Partial<PageQuerySummary>) {
  const current = state.querySummaries.value[index];
  if (!current) return;
  const source = (patch.source ?? current.source) as PageQuerySummarySource;
  const sourceOrFieldChanged =
    'source' in patch || 'fieldName' in patch || 'contributorKey' in patch || 'groupByField' in patch;
  if ('label' in patch) customSummaryKeys.add(current.key);
  const keepsDefaultLabel =
    !customSummaryKeys.has(current.key) && hasDefaultPageQuerySummaryLabel(current, summaryCatalog.value);
  state.replaceQuerySummaries(
    state.querySummaries.value.map((summary, candidateIndex) =>
      candidateIndex !== index
        ? summary
        : normalizeSummaryUpdate(summary, patch, source, keepsDefaultLabel, sourceOrFieldChanged),
    ),
  );
}

function normalizeSummaryUpdate(
  summary: PageQuerySummary,
  patch: Partial<PageQuerySummary>,
  source: PageQuerySummarySource,
  keepsDefaultLabel: boolean,
  sourceOrFieldChanged: boolean,
) {
  const next: PageQuerySummary = {
    ...summary,
    ...patch,
    source,
    ...(['SUM', 'GROUPED'].includes(source) ? {} : { fieldName: undefined }),
    ...(source === 'CONTRIBUTOR' ? {} : { contributorKey: undefined }),
    ...(source === 'GROUPED' ? {} : { groupByField: undefined }),
  };
  return {
    ...next,
    // A catalogue refresh must never rewrite a draft. Only a deliberate source/field action updates a default.
    label:
      sourceOrFieldChanged && keepsDefaultLabel && !('label' in patch)
        ? defaultPageQuerySummaryLabel(next, summaryCatalog.value)
        : next.label,
  };
}

function removeQuerySummary(index: number) {
  const removed = state.querySummaries.value[index];
  if (removed) customSummaryKeys.delete(removed.key);
  if (removed?.key === selectedSummaryKey.value) selectedSummaryKey.value = undefined;
  state.replaceQuerySummaries(
    state.querySummaries.value.filter((_, candidateIndex) => candidateIndex !== index),
  );
}

function moveQuerySummary(index: number, offset: number) {
  const summary = state.querySummaries.value[index];
  const targetIndex = index + offset;
  if (!summary || targetIndex < 0 || targetIndex >= state.querySummaries.value.length) return;
  reorderQuerySummary(summary.key, targetIndex);
}

function reorderQuerySummary(summaryKey: string, targetIndex: number) {
  if (isMutating.value) return;
  const sourceIndex = state.querySummaries.value.findIndex((summary) => summary.key === summaryKey);
  if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= state.querySummaries.value.length) return;
  const next = [...state.querySummaries.value];
  const [summary] = next.splice(sourceIndex, 1);
  next.splice(targetIndex, 0, summary);
  state.replaceQuerySummaries(next);
  selectedSummaryKey.value = summary.key;
  summaryFocusRequest.value += 1;
}

function toReferenceComposerField(field: PageReferenceField): PageComposerField | undefined {
  if (!field.id || !field.name) return undefined;
  return {
    id: field.id,
    title: field.label || field.name.split('.').at(-1) || field.name,
    fieldName: field.name,
    fieldSpecAlias: field.valueType,
    referenceModuleAlias: field.referenceModuleAlias,
    referenceCardinality: field.referenceCardinality,
    expandable: field.expandable === true && field.referenceCardinality === 'ONE',
    systemManaged: field.systemManaged,
    // Every non-root projection is server-derived.  The server also marks protected root fields.
    platformReadOnly: field.readOnly === true || field.name.includes('.'),
  };
}

async function loadReferenceDirectory(moduleAlias: string, path: string): Promise<PageComposerField[]> {
  const key = referenceDirectoryKey(moduleAlias, path);
  const cached = referenceFieldDirectories.value.get(key);
  if (cached) return cached;
  const pending = referenceDirectoryRequests.get(key);
  if (pending) return pending;
  const epoch = referenceDirectoryEpoch;
  let request!: Promise<PageComposerField[]>;
  request = moduleContext.http
    .request<{ moduleAlias: string; path?: string; fields?: PageReferenceField[] }>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/page-reference-fields${
        path ? `?path=${encodeURIComponent(path)}` : ''
      }`,
    })
    .then((response) => {
      const fields = (response.fields ?? [])
        .map(toReferenceComposerField)
        .filter((field): field is PageComposerField => field != null);
      if (epoch === referenceDirectoryEpoch && moduleAlias === props.moduleAlias) {
        const next = new Map(referenceFieldDirectories.value);
        next.set(key, fields);
        referenceFieldDirectories.value = next;
      }
      return fields;
    })
    .finally(() => {
      if (referenceDirectoryRequests.get(key) === request) referenceDirectoryRequests.delete(key);
    });
  referenceDirectoryRequests.set(key, request);
  return request;
}

async function loadReferenceChildren(
  node: UiTreeNode,
  request: UiTreeLoadRequest,
): Promise<UiTreeLoadResult> {
  const field = fieldOfMetadataNode(node);
  if (!field?.expandable || !field.referenceModuleAlias)
    return { mode: 'replace', nodes: [], hasMore: false };
  const moduleAlias = props.moduleAlias;
  const sequence = workspaceLoadSequence;
  try {
    const fields = await loadReferenceDirectory(moduleAlias, field.fieldName);
    if (request.signal.aborted || sequence !== workspaceLoadSequence || moduleAlias !== props.moduleAlias)
      return { mode: 'replace', nodes: [], hasMore: false };
    return {
      mode: 'replace',
      hasMore: false,
      nodes: filterSystemFields(fields).map(metadataFieldNode),
    };
  } catch (cause) {
    if (request.signal.aborted) return { mode: 'replace', nodes: [], hasMore: false };
    throw cause;
  }
}

function metadataFieldNode(field: PageComposerField): UiTreeNode {
  return {
    key: `metadata:field:${field.id}`,
    title: field.title,
    secondary: [
      field.fieldName,
      field.platformReadOnly ? '只读' : '',
      field.referenceModuleAlias ? '模块引用' : '',
    ]
      .filter(Boolean)
      .join(' · '),
    actions: [
      {
        key: 'add',
        title: `添加 ${field.title} 到…`,
        iconName: 'plus',
        disabled: isMutating.value,
        items: [
          ...(skeleton.value?.columns === false && !field.fieldName.includes('.')
            ? [
                { key: 'explorer-title', title: '用作导航标题' },
                { key: 'explorer-secondary', title: '用作辅助信息' },
              ]
            : [{ key: 'add-list', title: '添加到列表' }]),
          { key: 'add-form', title: '添加到表单' },
        ],
      },
    ],
    isLeaf: field.expandable ? false : true,
  };
}

async function ensureReferencePaths(fieldNames: readonly string[]) {
  const moduleAlias = props.moduleAlias;
  for (const fieldName of fieldNames) {
    const parts = fieldName.split('.');
    for (let index = 1; index < parts.length; index += 1) {
      const path = parts.slice(0, index).join('.');
      if (referenceFieldDirectories.value.has(referenceDirectoryKey(moduleAlias, path))) continue;
      try {
        await loadReferenceDirectory(moduleAlias, path);
      } catch {
        // Keep the saved path until the server has a resolvable directory for it.
      }
    }
  }
}

function filterSystemFields(fields: PageComposerField[]): PageComposerField[] {
  return showSystemFields.value ? fields : fields.filter((field) => !field.systemManaged);
}

function childRelationNodes(parentMetadataId?: string): UiTreeNode[] {
  if (!parentMetadataId) return [];
  return metadataRelations.value
    .filter(
      (candidate) =>
        candidate.relationRole !== 'main' &&
        candidate.relationRole !== 'MAIN' &&
        candidate.parentMetadataId === parentMetadataId,
    )
    .map((candidate) => {
      const relationId = candidate.id ?? candidate.metadataId;
      const fields = filterSystemFields(relationId ? (childMetadataFields.value.get(relationId) ?? []) : []);
      return {
        key: `metadata:relation:${relationId}`,
        title: candidate.title ?? candidate.relationAlias ?? '子实体',
        secondary: '子表',
        actions: [
          { key: 'add-form', title: '添加子表', iconName: 'plus' as const, disabled: isMutating.value },
        ],
        isLeaf: fields.length === 0,
        children: fields.map((field) => ({
          key: `metadata:relation-field:${relationId}:${field.id}`,
          title: field.title,
          secondary: [
            field.fieldName,
            field.platformReadOnly ? '只读' : '',
            field.referenceModuleAlias ? '模块引用' : '',
          ]
            .filter(Boolean)
            .join(' · '),
          actions: [
            { key: 'add-form', title: '添加到子表', iconName: 'plus' as const, disabled: isMutating.value },
          ],
          isLeaf: true,
        })),
      };
    });
}

async function loadComposition(requestSequence = workspaceLoadSequence, moduleAlias = props.moduleAlias) {
  if (requestSequence !== workspaceLoadSequence) return;
  const sequence = ++compositionLoadSequence;
  const current = () => requestSequence === workspaceLoadSequence && sequence === compositionLoadSequence;
  compositionLoading.value = true;
  try {
    const pages = await loadAllFromClient(pageClient(moduleAlias), [
      { fieldName: 'alias', operator: 'EQ', values: ['management'] },
    ]);
    if (!current()) return;
    const nextPage = pages[0];
    const variants = nextPage?.id
      ? await loadAllFromClient(variantClient(moduleAlias, nextPage.id), [
          { fieldName: 'clientType', operator: 'EQ', values: [pageCompositionTransport.webClient] },
          { fieldName: 'scopeType', operator: 'EQ', values: [pageCompositionTransport.globalScope] },
        ])
      : [];
    if (!current()) return;
    const nextVariant = variants[0];
    const [drafts, published] = nextVariant?.id
      ? await Promise.all([
          loadAllFromClient(revisionClient(nextVariant.id), [
            { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.draftRevision] },
          ]),
          loadAllFromClient(revisionClient(nextVariant.id), [
            { fieldName: 'status', operator: 'EQ', values: [pageCompositionTransport.publishedRevision] },
          ]),
        ])
      : [[], []];
    if (!current()) return;
    // Replace the working copy only after the complete snapshot arrives. A failed reload keeps local edits.
    resetPreviewDescriptor();
    page.value = nextPage;
    variant.value = nextVariant;
    revision.value = latestRevision(drafts);
    publishedRevision.value = latestRevision(published);
    draftConflict.value = false;
    removedDraft.value = undefined;
    draftParseError.value = undefined;
    state.replaceFields({ list: [], form: [] });
    state.updateQuickSearchPlaceholder(undefined);
    savedUiTreeJson.value = undefined;
    await hydrateDraft(revision.value);
    propertyDrawerOpen.value = false;
  } catch (cause) {
    if (current()) presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
  } finally {
    if (current()) compositionLoading.value = false;
  }
}

async function reloadComposition() {
  if (isMutating.value) return;
  const sequence = workspaceLoadSequence;
  if (hasUnsavedChanges.value) {
    const confirmed = await confirmAction({
      title: '加载最新草稿',
      content: '加载成功后将替换当前编排，并放弃尚未保存的本地修改。是否继续？',
      okText: '加载最新草稿',
    });
    if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  }
  await loadComposition();
}

function resetPreviewDescriptor() {
  previewRequestSequence += 1;
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = undefined;
  }
  previewDescriptor.value = undefined;
  previewStructure.value = undefined;
  previewLoading.value = false;
  previewError.value = undefined;
}

function schedulePreviewDescriptor() {
  const variantId = variant.value?.id;
  const revisionId = revision.value?.id;
  if (!variantId || !revisionId) {
    resetPreviewDescriptor();
    return;
  }
  const requestSequence = ++previewRequestSequence;
  if (previewDebounceTimer) clearTimeout(previewDebounceTimer);
  if (unavailableSources.value.length || draftParseError.value || propertyIssues.value.length) {
    previewLoading.value = false;
    previewError.value =
      draftParseError.value ??
      (propertyIssues.value.length
        ? '请修正列宽格式后继续预览。'
        : '页面包含失效来源，请移除标记节点并重新选择可用字段。');
    return;
  }
  previewLoading.value = true;
  previewError.value = undefined;
  const uiTreeJson = currentUiTreeJson.value;
  previewDebounceTimer = setTimeout(() => {
    previewDebounceTimer = undefined;
    void requestPreviewDescriptor(requestSequence, variantId, revisionId, uiTreeJson);
  }, 250);
}

function retryPreviewDescriptor() {
  if (previewLoading.value || !variant.value?.id || !revision.value?.id) return;
  schedulePreviewDescriptor();
}

async function requestPreviewDescriptor(
  requestSequence: number,
  variantId: string,
  revisionId: string,
  uiTreeJson: string,
) {
  try {
    const preview = await moduleContext.http.request<PresentationRevisionPreview>({
      method: 'POST',
      path: pageCompositionTransport.previewRevisionPath(variantId, revisionId),
      body: { uiTreeJson },
    });
    if (requestSequence !== previewRequestSequence) return;
    if (uiTreeJson !== currentUiTreeJson.value) return;
    previewStructure.value = {
      list: state.listFields.value,
      form: state.formFields.value,
      groups: state.formGroups.value,
      order: state.orderedForm.value,
      relations: state.formRelations.value,
    };
    previewDescriptor.value = preview.uiDescriptor;
    previewError.value = undefined;
  } catch (cause) {
    if (requestSequence !== previewRequestSequence) return;
    previewError.value = cause instanceof Error ? cause.message : '服务端未能解析当前草稿。';
  } finally {
    if (requestSequence === previewRequestSequence) previewLoading.value = false;
  }
}

async function loadAllFromClient<T>(
  client: ModuleCrudClient<T>,
  conditions: WebQueryCondition[] = [],
): Promise<T[]> {
  const response = await client.query({ unpaged: true, conditions });
  return response.records;
}

async function hydrateDraft(current: PresentationRevision | undefined, markSaved = true) {
  if (!current?.uiTreeJson) {
    state.replaceQuerySummaries([]);
    return;
  }
  const sequence = ++hydrateSequence;
  const workspaceSequence = workspaceLoadSequence;
  const moduleAlias = props.moduleAlias;
  const referenceEpoch = referenceDirectoryEpoch;
  try {
    const tree = JSON.parse(current.uiTreeJson) as {
      props?: { list?: { searchPlaceholder?: unknown } };
      querySummaries?: PageQuerySummary[];
      nodes?: Array<{
        slot?: PageComposerSlot;
        fields?: Array<string | { field?: string; props?: PageComposerFieldProperties }>;
        order?: Array<{ field?: string; group?: string }>;
        relations?: Array<{
          relation?: string;
          title?: string;
          fields?: Array<string | { field: string; props?: PageComposerFieldProperties }>;
        }>;
        groups?: Array<{
          group?: string;
          title?: string;
          subtitle?: string;
          fields?: Array<string | { field?: string; props?: PageComposerFieldProperties }>;
        }>;
      }>;
    };
    const modeTree = JSON.parse(current.uiTreeJson) as {
      templateVersion?: number;
      quickSearchFields?: string[];
      mode?: CompositionMode;
      actions?: PageCompositionActionPlacement[];
      nodes?: Array<{ slot: string; titleField?: string; secondaryField?: string }>;
    };
    const persistedFieldNames = (tree.nodes ?? [])
      .flatMap((node) => [
        ...(node.fields ?? []),
        ...(node.groups ?? []).flatMap((group) => group.fields ?? []),
      ])
      .map((entry) => (typeof entry === 'string' ? entry : entry.field))
      .filter((name): name is string => typeof name === 'string' && name.includes('.'));
    if (persistedFieldNames.length) await ensureReferencePaths(persistedFieldNames);
    if (
      sequence !== hydrateSequence ||
      workspaceSequence !== workspaceLoadSequence ||
      moduleAlias !== props.moduleAlias ||
      referenceEpoch !== referenceDirectoryEpoch
    )
      return;
    compositionMode.value = modeTree.mode ?? configuredMode.value;
    const explorer = modeTree.nodes?.find((node) => node.slot === 'explorer');
    explorerTitleField.value = explorer?.titleField ?? 'title';
    explorerSecondaryField.value = explorer?.secondaryField;
    const resolve = (slot: PageComposerSlot) => tree.nodes?.find((node) => node.slot === slot)?.fields ?? [];
    const fieldsByName = new Map(allMetadataFields.value.map((field) => [field.fieldName, field]));
    const resolveField = (
      entry: string | { field?: string; props?: PageComposerFieldProperties },
      includeReadOnly = false,
    ): PageComposerField | undefined => {
      const fieldName = typeof entry === 'string' ? entry : entry.field;
      const source = fieldName ? fieldsByName.get(fieldName) : undefined;
      if (!fieldName) return undefined;
      if (!source)
        return {
          id: `missing_${fieldName}`,
          title: fieldName,
          fieldName,
          unavailable: true,
          properties: typeof entry === 'string' ? undefined : entry.props,
        };
      const properties = {
        ...(typeof entry === 'string' ? {} : (entry.props ?? {})),
        ...(includeReadOnly && source.platformReadOnly ? { readOnly: true } : {}),
      };
      return {
        ...source,
        ...(Object.keys(properties).length ? { properties } : {}),
      };
    };
    state.replaceFields({
      list: resolve('list')
        .map((entry) => resolveField(entry, false))
        .filter((field): field is PageComposerField => Boolean(field)),
      order: tree.nodes
        ?.find((node) => node.slot === 'form')
        ?.order?.flatMap<PageComposerFormItem>((item) =>
          item.field
            ? [{ kind: 'field' as const, id: resolveField(item.field)!.id }]
            : item.group
              ? [{ kind: 'group' as const, id: item.group }]
              : [],
        ),
      form: resolve('form')
        .map((entry) => resolveField(entry, true))
        .filter((field): field is PageComposerField => Boolean(field)),
      relations: (tree.nodes?.find((node) => node.slot === 'form')?.relations ?? []).flatMap((entry) => {
        const relation = metadataRelations.value.find(
          (candidate) => candidate.relationAlias === entry.relation,
        );
        const relationCode = relation?.relationAlias ?? entry.relation;
        if (!relationCode) return [];
        return [
          {
            id: relation?.id ?? relationCode,
            relationCode,
            unavailable: !relation,
            title: entry.title?.trim() || relation?.title || relation?.relationAlias || relationCode,
            fields: (entry.fields ?? []).flatMap((entryField) => {
              const fieldName = typeof entryField === 'string' ? entryField : entryField.field;
              const properties = typeof entryField === 'string' ? undefined : entryField.props;
              const childField = relation
                ? childMetadataFields.value
                    .get(relation.id ?? relation.metadataId ?? '')
                    ?.find((candidate) => candidate.fieldName === fieldName)
                : undefined;
              return [
                {
                  ...(childField ?? {
                    id: `missing_${fieldName}`,
                    fieldName,
                    title: fieldName,
                    unavailable: true,
                  }),
                  properties,
                },
              ];
            }),
          },
        ];
      }),
      groups: (tree.nodes?.find((node) => node.slot === 'form')?.groups ?? []).flatMap((entry) => {
        if (!entry.group || !entry.title) return [];
        return [
          {
            id: entry.group,
            groupCode: entry.group,
            title: entry.title,
            subtitle: entry.subtitle,
            fields: (entry.fields ?? [])
              .map((entryField) => resolveField(entryField, true))
              .filter((field): field is PageComposerField => Boolean(field)),
          },
        ];
      }),
    });
    customSummaryKeys.clear();
    state.replaceQuerySummaries(Array.isArray(tree.querySummaries) ? tree.querySummaries : []);
    if (state.querySummaries.value.length) void loadSummaryCatalog();
    quickSearchFields.value =
      modeTree.quickSearchFields ??
      state.listFields.value
        .map((field) => field.fieldName)
        .filter((field) => searchableFields.value.includes(field));
    actionPlacements.value = (modeTree.actions ?? []).filter(
      (placement): placement is PageCompositionActionPlacement =>
        typeof placement?.actionCode === 'string' &&
        typeof placement?.anchor === 'string' &&
        ['page', 'detail', 'form'].includes(placement.anchor),
    );
    if (modeTree.templateVersion === 4)
      actionPlacements.value = withStandardActionEntries(actionPlacements.value, moduleActions.value);
    if ((modeTree.templateVersion ?? 1) < 4) {
      const defaults = defaultPageActionEntries(moduleActions.value);
      actionPlacements.value = [
        ...actionPlacements.value,
        ...defaults.filter(
          (entry) =>
            !actionPlacements.value.some(
              (existing) => existing.anchor === entry.anchor && existing.actionCode === entry.actionCode,
            ),
        ),
      ];
    }
    state.updateQuickSearchPlaceholder(
      typeof tree.props?.list?.searchPlaceholder === 'string' ? tree.props.list.searchPlaceholder : undefined,
    );
    if (markSaved) savedUiTreeJson.value = currentUiTreeJson.value;
    draftParseError.value = undefined;
  } catch {
    draftParseError.value = '草稿结构无法解析，当前内容不会覆盖已保存配置。请修复该修订后重新加载。';
  }
}

async function initializeComposition() {
  if (isMutating.value || !relation.value?.id) return;
  const sequence = workspaceLoadSequence;
  const current = () => sequence === workspaceLoadSequence;
  const moduleAlias = props.moduleAlias;
  saving.value = true;
  try {
    if (!page.value) {
      const createdPage = (
        await pageClient(moduleAlias).insert({
          alias: 'management',
          contractType: pageCompositionTransport.managementContract,
          mainRelationId: relation.value.id,
          title: `${props.moduleTitle ?? props.moduleAlias}管理页`,
          enabled: true,
        })
      ).record;
      if (!current()) return;
      page.value = createdPage;
    }
    if (!page.value?.id) return;
    if (!variant.value) {
      const createdVariant = (
        await variantClient(moduleAlias, page.value.id).insert({
          clientType: pageCompositionTransport.webClient,
          scopeType: pageCompositionTransport.globalScope,
          title: 'Web 全局呈现',
          enabled: true,
        })
      ).record;
      if (!current()) return;
      variant.value = createdVariant;
    }
    if (!variant.value?.id || revision.value) return;
    const revisions = await loadAllFromClient(revisionClient(variant.value.id));
    if (!current()) return;
    const latestPublished = latestRevision(
      revisions.filter((item) => item.status === pageCompositionTransport.publishedRevision),
    );
    if (latestPublished) await hydrateDraft(latestPublished, false);
    else actionPlacements.value = defaultPageActionEntries(moduleActions.value);
    const treeJsonToPersist = currentUiTreeJson.value;
    const createdRevision = (
      await revisionClient(variant.value.id).insert({
        revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
        templateAlias: latestPublished?.templateAlias ?? 'management',
        templateVersion: skeleton.value ? 4 : (latestPublished?.templateVersion ?? 1),
        uiTreeJson: treeJsonToPersist,
        status: pageCompositionTransport.draftRevision,
        title: latestPublished ? `基于 v${latestPublished.revisionNo ?? 1} 的草稿` : '初始草稿',
        enabled: true,
      })
    ).record;
    if (!current()) return;
    revision.value = createdRevision;
    if (latestPublished) publishedRevision.value = latestPublished;
    await hydrateDraft(revision.value);
  } catch (cause) {
    if (!current()) return;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    if (current()) saving.value = false;
  }
}

async function saveDraft(
  allowDuringPublish = false,
  treeJsonToPersist = currentUiTreeJson.value,
): Promise<boolean> {
  if (state.querySummaries.value.length && !summaryCatalog.value && !summaryCatalogLoading.value)
    await loadSummaryCatalog();
  if (
    draftParseError.value ||
    propertyIssues.value.length > 0 ||
    actionIssues.value.length > 0 ||
    hasSummaryIssues.value ||
    (summarySources.value.length > 0 && summaryCatalogLoading.value) ||
    (summarySources.value.length > 0 && !supportsQuerySummaries.value) ||
    (summarySources.value.length > 0 && Boolean(summaryCatalogError.value)) ||
    draftConflict.value ||
    compositionLoading.value ||
    loading.value ||
    saving.value ||
    (!allowDuringPublish && publishing.value) ||
    !revision.value?.id ||
    !variant.value?.id
  )
    return false;
  const sequence = workspaceLoadSequence;
  const variantId = variant.value.id;
  const candidate = revision.value;
  const current = () => sequence === workspaceLoadSequence;
  saving.value = true;
  try {
    const result = await revisionClient(variantId).update(candidate.id!, {
      ...candidate,
      templateVersion: skeleton.value ? 4 : candidate.templateVersion,
      uiTreeJson: treeJsonToPersist,
    });
    if (!current()) return false;
    revision.value = result.record;
    savedUiTreeJson.value = treeJsonToPersist;
    removedDraft.value = undefined;
    return true;
  } catch (cause) {
    if (!current()) return false;
    if (normalizeError(cause).code === platformErrorCodes.conflictVersion) draftConflict.value = true;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
    return false;
  } finally {
    if (current()) saving.value = false;
  }
}

async function publishDraft() {
  if (state.querySummaries.value.length && !summaryCatalog.value && !summaryCatalogLoading.value)
    await loadSummaryCatalog();
  if (
    isMutating.value ||
    propertyIssues.value.length > 0 ||
    actionIssues.value.length > 0 ||
    draftConflict.value ||
    unavailableSources.value.length ||
    hasSummaryIssues.value ||
    (summarySources.value.length > 0 && summaryCatalogLoading.value) ||
    (summarySources.value.length > 0 && !supportsQuerySummaries.value) ||
    (summarySources.value.length > 0 && Boolean(summaryCatalogError.value)) ||
    draftParseError.value ||
    !revision.value?.id
  )
    return;
  const sequence = workspaceLoadSequence;
  const current = () => sequence === workspaceLoadSequence;
  const variantId = variant.value?.id;
  if (!variantId) return;
  const confirmed = await confirmAction({
    title: '发布页面修订',
    content: `将发布“${page.value?.title ?? '管理页'}”的草稿 v${revision.value.revisionNo}，目标为 Web · 全局，模板为 ${revision.value.templateAlias ?? 'management'} v${revision.value.templateVersion ?? 1}。发布会先保存并校验页面结构，随后替换该目标当前的已发布修订。是否继续？`,
    okText: '确认发布',
  });
  if (!confirmed || !current() || isMutating.value) return;
  const treeJsonToPublish = currentUiTreeJson.value;
  publishing.value = true;
  try {
    if (!(await saveDraft(true, treeJsonToPublish)) || !current()) return;
    const publicationCandidate = revision.value;
    await moduleContext.http.request<number>({
      method: 'POST',
      path: `/platform.presentation_publish/revisions/${encodeURIComponent(publicationCandidate.id!)}/publish`,
    });
    if (current()) {
      // A successful publication makes this revision immutable, even if the following read fails.
      publishedRevision.value = {
        ...publicationCandidate,
        status: pageCompositionTransport.publishedRevision,
      };
      revision.value = undefined;
      removedDraft.value = undefined;
      propertyDrawerOpen.value = false;
    }
    try {
      const nextDraft = await createFollowUpDraft(variantId, publicationCandidate, treeJsonToPublish);
      if (current()) {
        revision.value = nextDraft;
        await hydrateDraft(nextDraft);
      }
    } catch {
      if (!current()) return;
      await loadComposition();
      if (!current()) return;
      presentPlatformError(
        new Error(
          `草稿 v${publicationCandidate.revisionNo ?? 1} 已发布，但未能生成后续草稿；请基于最近发布修订重新创建草稿。`,
        ),
        { source: 'page-composition', phase: 'action' },
      );
      return;
    }
    if (current()) await loadComposition();
  } catch (cause) {
    if (!current()) return;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    if (current()) publishing.value = false;
  }
}

async function discardUnsavedChanges() {
  if (!revision.value || !hasUnsavedChanges.value || isMutating.value) return;
  const sequence = workspaceLoadSequence;
  const confirmed = await confirmAction({
    title: '放弃本次更改',
    content: `将撤销当前草稿 v${revision.value.revisionNo} 尚未保存的本地调整，已保存的草稿内容不会受影响。是否继续？`,
    okText: '放弃更改',
  });
  if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  await hydrateDraft(revision.value);
  removedDraft.value = undefined;
  propertyDrawerOpen.value = false;
}

/** Keeps a stable editable working copy after an immutable revision becomes published. */
async function createFollowUpDraft(
  variantId: string,
  publishedRevision: PresentationRevision,
  uiTreeJson: string,
) {
  const revisions = await loadAllFromClient(revisionClient(variantId));
  return (
    await revisionClient(variantId).insert({
      revisionNo: Math.max(0, ...revisions.map((item) => item.revisionNo ?? 0)) + 1,
      templateAlias: publishedRevision.templateAlias ?? 'management',
      templateVersion: publishedRevision.templateVersion ?? 1,
      uiTreeJson,
      status: pageCompositionTransport.draftRevision,
      title: `基于 v${publishedRevision.revisionNo ?? 1} 的草稿`,
      enabled: true,
    })
  ).record;
}

function latestRevision(revisions: PresentationRevision[]) {
  return [...revisions].sort((left, right) => (right.revisionNo ?? 0) - (left.revisionNo ?? 0))[0];
}

async function loadAll<T>(path: string): Promise<T[]> {
  const records: T[] = [];
  for (let pageNum = 1; ; pageNum += 1) {
    const response = await moduleContext.http.request<WebPageResponse<T>>({
      method: 'POST',
      path,
      body: { page: { pageNum, pageSize: 200 } },
    });
    records.push(...response.records);
    if (response.totalKnown ? pageNum >= response.pages : response.records.length < 200) return records;
  }
}

function toComposerField(field: MetadataField): PageComposerField | undefined {
  if (!field.id || !field.fieldName) return undefined;
  return {
    id: field.id,
    title: field.title ?? field.fieldName,
    fieldName: field.fieldName,
    fieldSpecAlias: field.fieldSpecAlias,
    systemManaged: field.systemManaged,
    required: field.required,
    platformReadOnly: platformFieldPolicies.value.get(field.fieldName)?.readOnly,
    referenceModuleAlias: platformFieldPolicies.value.get(field.fieldName)?.referenceModuleAlias,
  };
}

function isRuntimeReservedMetadataField(field: MetadataField) {
  return platformFieldPolicies.value.get(field.fieldName ?? '')?.composable === false;
}

function slotTitle(slot: PageComposerSlot) {
  return slot === 'list' ? '列表' : '详情 / 表单';
}
function fieldsInSlot(slot: PageComposerSlot) {
  return slot === 'list' ? state.listFields.value : state.formFields.value;
}
function selectMetadataNode(node: UiTreeNode) {
  if (!isMutating.value) selectedMetadataTreeKey.value = node.key;
}
function addMetadataNode(action: UiRecordInlineAction, node: UiTreeNode) {
  if (isMutating.value || action.disabled) return;
  const payload = metadataDragPayload(node);
  if (payload)
    handleCompositionMetadataDrop(
      {
        kind:
          action.key === 'explorer-title' || action.key === 'explorer-secondary'
            ? action.key
            : action.key === 'add-list'
              ? 'list'
              : 'form',
      },
      payload,
    );
}

function selectUiTreeKey(key: string) {
  const summaryMatch = /^ui:summary:(.+)$/.exec(key);
  if (summaryMatch) {
    openSummaryEditor(summaryMatch[1]);
    return;
  }
  selectedSummaryKey.value = undefined;
  selectedActionKey.value = key.startsWith('ui:action:') ? key : undefined;
  if (selectedActionKey.value) {
    state.selectedNodeId.value = undefined;
    const [, , anchor] = key.split(':');
    state.previewMode.value =
      anchor === 'form' ? 'edit' : anchor === 'detail' ? 'detail' : hasListPreview.value ? 'list' : 'detail';
    return;
  }

  const parsed = parseUiNode(key);
  if (!parsed) return;
  if (parsed.kind === 'slot' || parsed.kind === 'fieldGroup') {
    selectNode({ id: `slot:${parsed.slot}`, kind: 'slot', title: slotTitle(parsed.slot), slot: parsed.slot });
    return;
  }
  if (parsed.kind === 'template') {
    selectNode({ id: 'template:list:quick-search', kind: 'template', title: '快速查询', slot: 'list' });
    return;
  }
  if (parsed.kind === 'relation') {
    const relation = state.formRelations.value.find((candidate) => candidate.id === parsed.relationId);
    if (relation)
      selectNode({
        id: `form:relation:${relation.id}`,
        kind: 'relation',
        title: relation.title,
        slot: 'form',
        relation,
      });
    return;
  }
  if (parsed.kind === 'group') {
    const group = state.formGroups.value.find((candidate) => candidate.id === parsed.groupId);
    if (group)
      selectNode({ id: `form:group:${group.id}`, kind: 'group', title: group.title, slot: 'form', group });
    return;
  }
  if (parsed.kind === 'groupField') {
    const group = state.formGroups.value.find((candidate) => candidate.id === parsed.groupId);
    const field = group?.fields.find((candidate) => candidate.id === parsed.fieldId);
    if (group && field)
      selectNode({
        id: `form:group:${group.id}:field:${field.id}`,
        kind: 'groupField',
        title: field.title,
        slot: 'form',
        group,
        field,
      });
    return;
  }
  if (parsed.kind === 'relationField') {
    const relation = state.formRelations.value.find((candidate) => candidate.id === parsed.relationId);
    const field = relation?.fields.find((candidate) => candidate.id === parsed.fieldId);
    if (relation && field)
      selectNode({
        id: `form:relation:${relation.id}:field:${field.id}`,
        kind: 'relationField',
        title: field.title,
        slot: 'form',
        relation,
        relationField: field,
      });
    return;
  }
  if (parsed.kind !== 'field') return;
  const field = fieldsInSlot(parsed.slot).find((candidate) => candidate.id === parsed.fieldId);
  if (field)
    selectNode({
      id: `${parsed.slot}:${field.id}`,
      kind: 'field',
      title: field.title,
      slot: parsed.slot,
      field,
    });
}

function canDragPaletteAction(action: ModuleRuntimeAction) {
  return (
    action.category === 'CUSTOM' &&
    (['page', 'detail', 'form'] as const).some((anchor) => canPlaceActionInAnchor(action, anchor))
  );
}

function canDragMetadataNode(node: UiTreeNode) {
  const payload = metadataDragPayload(node);
  if (payload?.kind === 'action') {
    const action = moduleActions.value.find((action) => action.actionCode === payload.actionCode);
    return !isMutating.value && !!action && canDragPaletteAction(action);
  }
  return !isMutating.value && payload != null;
}

function handleUiTreeDoubleClick(key: string) {
  if (key === 'ui:template:list:query-summaries') {
    openSummaryEditor();
    return;
  }
  const summaryMatch = /^ui:summary:(.+)$/.exec(key);
  if (summaryMatch) {
    openSummaryEditor(summaryMatch[1]);
    return;
  }
  selectUiTreeKey(key);
  if (
    selectedActionEntry.value ||
    ['field', 'groupField', 'relationField', 'group', 'template'].includes(parseUiNode(key)?.kind ?? '')
  )
    openPropertyDrawer();
}

function reorderListField(fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveField(fieldId, 'list', 'list', targetIndex);
}

function reorderFormField(fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveField(fieldId, 'form', 'form', targetIndex);
}

function moveFormFieldToGroup(fieldId: string, groupId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormFieldToGroup(fieldId, groupId, targetIndex);
}

function moveGroupFieldToForm(groupId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveGroupFieldToForm(groupId, fieldId, targetIndex);
}

function reorderGroupField(groupId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveGroupField(groupId, fieldId, targetIndex);
}

function moveGroupFieldToGroup(
  sourceGroupId: string,
  fieldId: string,
  targetGroupId: string,
  targetIndex: number,
) {
  if (!isMutating.value) state.moveGroupFieldToGroup(sourceGroupId, fieldId, targetGroupId, targetIndex);
}

function reorderGroup(groupId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormGroup(groupId, targetIndex);
}

function reorderRelationField(relationId: string, fieldId: string, targetIndex: number) {
  if (!isMutating.value) state.moveFormRelationField(relationId, fieldId, targetIndex);
}

function handleCompositionMetadataDrop(target: ComposerDropTarget, payload: unknown) {
  if (isMutating.value) return;
  const metadata = parseMetadataDragPayload(payload);
  if (!metadata) return;
  if (target.kind === 'quick-search') {
    if (metadata.kind !== 'field') return;
    const field = allMetadataFields.value.find((item) => item.id === metadata.fieldId);
    if (
      field &&
      searchableFields.value.includes(field.fieldName) &&
      !quickSearchFields.value.includes(field.fieldName)
    )
      quickSearchFields.value.push(field.fieldName);
    return;
  }
  if (target.kind === 'explorer-title' || target.kind === 'explorer-secondary') {
    if (metadata.kind !== 'field') return;
    const field = allMetadataFields.value.find((item) => item.id === metadata.fieldId);
    if (!field || skeleton.value?.columns || field.fieldName.includes('.')) return;
    if (target.kind === 'explorer-title') explorerTitleField.value = field.fieldName;
    else explorerSecondaryField.value = field.fieldName;
    return;
  }
  if (metadata.kind === 'field') {
    if (target.kind === 'action-anchor') return;
    const field = allMetadataFields.value.find((candidate) => candidate.id === metadata.fieldId);
    if (!field || target.kind === 'relation') return;
    if (target.kind === 'group') placeMetadataFieldInGroup(field, target.groupId, target.index);
    else {
      const slot = target.kind;
      const sourceGroup =
        slot === 'form' &&
        state.formGroups.value.find((group) => group.fields.some((item) => item.id === field.id));
      if (sourceGroup && target.index !== undefined)
        state.moveGroupFieldToForm(sourceGroup.id, field.id, target.index);
      else {
        state.addField(field, slot, target.index);
        if (target.index !== undefined) state.moveField(field.id, slot, slot, target.index);
      }
    }
  } else if (metadata.kind === 'relation' && target.kind === 'form') {
    addRelationById(metadata.relationId);
  } else if (
    metadata.kind === 'relationField' &&
    (target.kind === 'form' || (target.kind === 'relation' && target.relationId === metadata.relationId))
  ) {
    addRelationFieldById(metadata.relationId, metadata.fieldId);
    if (target.index !== undefined)
      state.moveFormRelationField(metadata.relationId, metadata.fieldId, target.index);
  }
}

function handleCompositionSourceDrop(target: ComposerDropTarget, payload: unknown) {
  const source = parsePageCompositionDragPayload(payload);
  if (!source) return;
  if (source.kind !== 'action') {
    handleCompositionMetadataDrop(target, source);
    return;
  }
  if (isMutating.value || target.kind !== 'action-anchor') return;
  const action = moduleActions.value.find((candidate) => candidate.actionCode === source.actionCode);
  if (!action || !actionCanOccupyAnchor(action, target.anchor)) return;
  if (target.anchor === 'form') {
    actionFormMode.value = source.actionCode === 'create' ? 'create' : 'edit';
  }
  state.previewMode.value =
    target.anchor === 'form'
      ? 'edit'
      : target.anchor === 'detail'
        ? 'detail'
        : hasListPreview.value
          ? 'list'
          : 'detail';
  handlePreviewActionDrop(source, {
    anchor: target.anchor,
    index:
      target.index ??
      actionButtons(actionPlacements.value.filter((entry) => entry.anchor === target.anchor)).length,
  });
}

/** C is an editing surface too: palette drops and internal moves update the same managed entry list as B. */
function handlePreviewActionDrop(
  source: { actionCode: string; sourceAnchor?: PageCompositionActionPlacement['anchor'] },
  target: { anchor: PageCompositionActionPlacement['anchor']; index: number },
  visibleOnly = false,
) {
  if (isMutating.value) return;
  const action = moduleActions.value.find((candidate) => candidate.actionCode === source.actionCode);
  if (!action || !actionCanOccupyAnchor(action, target.anchor)) return;
  const previous = actionPlacements.value.find(
    (entry) =>
      entry.actionCode === source.actionCode && entry.anchor === (source.sourceAnchor ?? target.anchor),
  );
  if (source.sourceAnchor && source.sourceAnchor !== target.anchor && action.category !== 'CUSTOM') return;
  const members = previous
    ? actionButtonMembers(actionPlacements.value, previous)
    : [{ actionCode: source.actionCode, anchor: target.anchor, title: action.title }];
  const without = actionPlacements.value.filter(
    (entry) =>
      !members.includes(entry) && !(entry.anchor === target.anchor && entry.actionCode === source.actionCode),
  );
  const targetButtons = actionButtons(
    without.filter((entry) => entry.anchor === target.anchor && (!visibleOnly || !entry.hidden)),
  );
  const before = targetButtons[Math.max(0, target.index)];
  const insertion = before ? without.indexOf(before) : without.length;
  actionPlacements.value = [
    ...without.slice(0, insertion),
    ...members.map((entry) => ({ ...entry, anchor: target.anchor })),
    ...without.slice(insertion),
  ];
}

/**
 * Anchors own an interaction scope. A page button cannot silently acquire a selected record,
 * and a form button cannot run an unrelated record operation against unsaved values.
 */
function actionCanOccupyAnchor(
  action: ModuleRuntimeAction,
  anchor: PageCompositionActionPlacement['anchor'],
) {
  return canPlaceActionInAnchor(action, anchor);
}

/**
 * A metadata field is a source that may be projected once in the form slot. Dropping it onto a
 * group therefore means "place it here": use the existing typed move commands when it already
 * has a form placement, and only add it before moving when it is new to the form.
 */
function placeMetadataFieldInGroup(field: PageComposerField, groupId: string, targetIndex?: number) {
  if (!state.formGroups.value.some((group) => group.id === groupId)) return;
  const sourceGroup = state.formGroups.value.find((group) =>
    group.fields.some((candidate) => candidate.id === field.id),
  );
  if (sourceGroup) {
    if (sourceGroup.id !== groupId)
      state.moveGroupFieldToGroup(sourceGroup.id, field.id, groupId, targetIndex);
    else if (targetIndex !== undefined) state.moveGroupField(groupId, field.id, targetIndex);
    else state.addField(field, 'form');
    return;
  }
  if (state.formFields.value.some((candidate) => candidate.id === field.id)) {
    state.moveFormFieldToGroup(field.id, groupId, targetIndex);
    return;
  }
  state.addField(field, 'form');
  state.moveFormFieldToGroup(field.id, groupId, targetIndex);
}

function handlePreviewPlacement(source: CompositionPlacementSource, target: CompositionPlacementTarget) {
  if (isMutating.value || previewLoading.value || previewError.value) return;
  const model = {
    list: state.listFields.value,
    form: state.formFields.value,
    groups: state.formGroups.value,
    order: state.orderedForm.value,
    relations: state.formRelations.value,
  };
  const placement = resolveCompositionPlacement(model, source, target);
  if (!placement) return;
  const { container, index } = placement;
  const mode = state.previewMode.value;
  if (source.kind === 'metadata') {
    const metadata = source.metadata;
    if (container.kind === 'list' || container.kind === 'form' || container.kind === 'group') {
      handleCompositionMetadataDrop({ ...container, index }, metadata);
    } else if (container.kind === 'relation' && metadata.kind === 'relationField') {
      addRelationFieldById(metadata.relationId, metadata.fieldId);
      state.moveFormRelationField(metadata.relationId, metadata.fieldId, index);
    } else if (container.kind === 'relations' && metadata.kind !== 'field') {
      if (metadata.kind === 'relation') addRelationById(metadata.relationId);
      else addRelationFieldById(metadata.relationId, metadata.fieldId);
      state.moveFormRelation(metadata.relationId, index);
    }
  } else {
    const from = source.container;
    if (container.kind === 'groups' || (container.kind === 'form' && from.kind === 'groups'))
      state.moveFormGroup(source.nodeId, index);
    else if (container.kind === 'relations') state.moveFormRelation(source.nodeId, index);
    else if (container.kind === 'relation')
      state.moveFormRelationField(container.relationId, source.nodeId, index);
    else if (container.kind === 'group') {
      if (from.kind === 'group')
        state.moveGroupFieldToGroup(from.groupId, source.nodeId, container.groupId, index);
      else state.moveFormFieldToGroup(source.nodeId, container.groupId, index);
    } else if (container.kind === 'form' && from.kind === 'group')
      state.moveGroupFieldToForm(from.groupId, source.nodeId, index);
    else if (container.kind === 'list' || container.kind === 'form')
      state.moveField(source.nodeId, container.kind, container.kind, index);
  }
  // Detail and form are views of one form slot. A placement must not change the user's view.
  state.previewMode.value = mode;
}

function metadataDragPayload(node: UiTreeNode): PageCompositionDragPayload | undefined {
  const actionMatch = /^module-action:(.+)$/.exec(node.key);
  if (actionMatch) return { kind: 'action', actionCode: actionMatch[1] };
  const mainField = fieldOfMetadataNode(node);
  if (mainField)
    return {
      kind: 'field',
      fieldId: mainField.id,
      fieldName: mainField.fieldName,
      title: mainField.title,
      fieldSpecAlias: mainField.fieldSpecAlias,
      required: mainField.required,
      readOnly: mainField.platformReadOnly,
    };
  const relationMatch = /^metadata:relation:(.+)$/.exec(node.key);
  if (relationMatch) return { kind: 'relation', relationId: relationMatch[1] };
  const childFieldMatch = /^metadata:relation-field:(.+):(.+)$/.exec(node.key);
  return childFieldMatch
    ? { kind: 'relationField', relationId: childFieldMatch[1], fieldId: childFieldMatch[2] }
    : undefined;
}

function addRelationById(relationId: string) {
  const selected = metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === relationId,
  );
  if (!selected?.relationAlias || selected.parentMetadataId !== relation.value?.metadataId) return;
  state.addFormRelation({
    id: selected.id ?? selected.metadataId ?? selected.relationAlias,
    relationCode: selected.relationAlias,
    title: selected.title ?? selected.relationAlias,
    fields: [],
  });
}

function addRelationFieldById(relationId: string, fieldId: string) {
  const selected = metadataRelations.value.find(
    (candidate) => (candidate.id ?? candidate.metadataId) === relationId,
  );
  const field = childMetadataFields.value.get(relationId)?.find((candidate) => candidate.id === fieldId);
  if (!selected?.relationAlias || !field || selected.parentMetadataId !== relation.value?.metadataId) return;
  state.addFormRelationField(
    {
      id: selected.id ?? selected.metadataId ?? selected.relationAlias,
      relationCode: selected.relationAlias,
      title: selected.title ?? selected.relationAlias,
      fields: [],
    },
    field,
  );
}

function fieldOfMetadataNode(node: UiTreeNode) {
  const prefix = 'metadata:field:';
  if (!node.key.startsWith(prefix)) return undefined;
  return allMetadataFields.value.find((field) => field.id === node.key.slice(prefix.length));
}

function parseUiNode(
  key: string,
):
  | { kind: 'slot'; slot: PageComposerSlot }
  | { kind: 'fieldGroup'; slot: 'list' }
  | { kind: 'group'; groupId: string }
  | { kind: 'groupField'; groupId: string; fieldId: string }
  | { kind: 'template' }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string }
  | { kind: 'field'; slot: PageComposerSlot; fieldId: string }
  | undefined {
  if (key === 'ui:template:list:quick-search') return { kind: 'template' };
  const groupMatch = /^ui:group:form:(.+)$/.exec(key);
  if (groupMatch) return { kind: 'group', groupId: groupMatch[1] };
  const groupFieldMatch = /^ui:group-field:form:(.+):(.+)$/.exec(key);
  if (groupFieldMatch)
    return { kind: 'groupField', groupId: groupFieldMatch[1], fieldId: groupFieldMatch[2] };
  const relationMatch = /^ui:relation:form:(.+)$/.exec(key);
  if (relationMatch) return { kind: 'relation', relationId: relationMatch[1] };
  const relationFieldMatch = /^ui:relation-field:form:(.+):(.+)$/.exec(key);
  if (relationFieldMatch)
    return { kind: 'relationField', relationId: relationFieldMatch[1], fieldId: relationFieldMatch[2] };
  if (key === 'ui:slot:list:fields') return { kind: 'fieldGroup', slot: 'list' };
  const slotMatch = /^ui:slot:(list|form)$/.exec(key);
  if (slotMatch) return { kind: 'slot', slot: slotMatch[1] as PageComposerSlot };
  const fieldMatch = /^ui:field:(list|form):(.+)$/.exec(key);
  if (fieldMatch) return { kind: 'field', slot: fieldMatch[1] as PageComposerSlot, fieldId: fieldMatch[2] };
  return undefined;
}

function selectNode(node: (typeof state.nodes.value)[number]) {
  if (isMutating.value) return;
  selectedActionKey.value = undefined;
  state.selectNode(node);
}

function selectDescriptorPreviewField(slot: PageComposerSlot, fieldName: string, configure = false) {
  if (isMutating.value) return;
  const node = state.nodes.value.find(
    (candidate) => candidate.slot === slot && candidate.field?.fieldName === fieldName,
  );
  if (!node) return;
  // Selecting within the preview must keep that surface mounted between the two clicks.
  const mode = state.previewMode.value;
  selectNode(node);
  state.previewMode.value = mode;
  if (configure) openPropertyDrawer();
}

function configurePreviewRelationField(relationCode: string, fieldName: string) {
  if (isMutating.value) return;
  const node = state.nodes.value.find(
    (candidate) =>
      candidate.kind === 'relationField' &&
      candidate.relation?.relationCode === relationCode &&
      candidate.relationField?.fieldName === fieldName,
  );
  if (!node) return;
  const mode = state.previewMode.value;
  selectNode(node);
  state.previewMode.value = mode;
  openPropertyDrawer();
}

function selectPreviewMode(key: string) {
  if (
    !previewDescriptor.value ||
    isMutating.value ||
    (!['detail', 'edit'].includes(key) && !(key === 'list' && hasListPreview.value))
  )
    return;
  state.previewMode.value = key as typeof state.previewMode.value;
}

function handleNodeAction(action: 'configure' | 'remove' | 'add-group' | 'toggle-visibility', key: string) {
  if (isMutating.value) return;
  if (key === 'ui:template:list:query-summaries' && action === 'configure') {
    openSummaryEditor();
    return;
  }
  const summaryMatch = /^ui:summary:(.+)$/.exec(key);
  if (summaryMatch && action === 'configure') {
    openSummaryEditor(summaryMatch[1]);
    return;
  }
  if (action === 'add-group') {
    state.addFormGroup();
    openPropertyDrawer();
    return;
  }
  if (action === 'remove' && key.startsWith('ui:binding:')) {
    const [, , role, fieldName] = key.split(':');
    if (role === 'explorer-title') explorerTitleField.value = '';
    else if (role === 'explorer-secondary') explorerSecondaryField.value = undefined;
    else quickSearchFields.value = quickSearchFields.value.filter((field) => field !== fieldName);
    return;
  }
  if (action === 'toggle-visibility' && key.startsWith('ui:action:')) {
    const [, , anchor, actionCode] = key.split(':');
    const entry = actionPlacements.value.find(
      (entry) => entry.anchor === anchor && entry.actionCode === actionCode,
    );
    if (!entry) return;
    const members = actionButtonMembers(actionPlacements.value, entry);
    const hidden = !members.every((member) => member.hidden);
    members.forEach((member) => {
      member.hidden = hidden;
    });
    return;
  }
  if (action === 'remove' && key.startsWith('ui:action:')) {
    const [, , anchor, actionCode] = key.split(':');
    actionPlacements.value = actionPlacements.value.filter(
      (placement) => placement.actionCode !== actionCode || placement.anchor !== anchor,
    );
    return;
  }
  selectUiTreeKey(key);
  if (selectedUiTreeKey.value !== key) return;
  if (action === 'configure') {
    openPropertyDrawer();
    return;
  }
  const before = currentUiTreeJson.value;
  state.removeSelectedField();
  propertyDrawerOpen.value = false;
  const after = currentUiTreeJson.value;
  if (before !== after) removedDraft.value = { before, after };
}
function undoRemoval() {
  if (isMutating.value || !removedDraft.value) return;
  const before = removedDraft.value.before;
  removedDraft.value = undefined;
  void hydrateDraft({ uiTreeJson: before }, false);
}
function updateFieldProperty<K extends keyof PageComposerFieldProperties>(
  key: K,
  value: PageComposerFieldProperties[K],
) {
  if (isMutating.value) return;
  propertyDraft.value = { ...propertyDraft.value, [key]: value };
  state.updateSelectedFieldProperties(propertyDraft.value);
}
function updateQuickSearch(value: string) {
  if (isMutating.value) return;
  quickSearchPlaceholderDraft.value = value;
  state.updateQuickSearchPlaceholder(value);
}
function updateGroup(title: string, subtitle: string) {
  if (isMutating.value || !selectedGroup.value) return;
  groupTitleDraft.value = title;
  groupSubtitleDraft.value = subtitle;
  state.updateFormGroup(selectedGroup.value.id, title, subtitle);
}

function fieldDisplayTitle(field: PageComposerField) {
  return field.properties?.label ?? field.title;
}

function openPropertyDrawer() {
  if (
    isMutating.value ||
    (!selectedActionEntry.value && !selectedField.value && !selectedQuickSearch.value && !selectedGroup.value)
  )
    return;
  if (selectedQuickSearch.value) quickSearchPlaceholderDraft.value = state.quickSearchPlaceholder.value ?? '';
  else if (selectedField.value) propertyDraft.value = { ...(selectedField.value.properties ?? {}) };
  else if (selectedGroup.value) {
    groupTitleDraft.value = selectedGroup.value.title;
    groupSubtitleDraft.value = selectedGroup.value.subtitle ?? '';
  }
  summaryDrawerOpen.value = false;
  selectedSummaryKey.value = undefined;
  propertyDrawerOpen.value = true;
}
</script>

<template>
  <section class="page-composition-workspace">
    <ManagementPanelHeader :title="composerTitle" :subtitle="compositionSubtitle">
      <template #title-suffix>
        <UiRadioGroup
          v-model:value="editorMode"
          :options="editorModeOptions"
          size="small"
          :disabled="isMutating"
        />
      </template>
      <template #actions>
        <div class="page-composition-actions">
          <UiButton v-if="removedDraft" :disabled="isMutating" @click="undoRemoval">撤销移除</UiButton>
          <UiButton
            v-if="!revision"
            :loading="saving"
            :disabled="isMutating || !relation"
            type="primary"
            @click="initializeComposition"
          >
            {{ publishedRevision ? '基于已发布版本创建草稿' : '初始化页面' }}
          </UiButton>
          <template v-else>
            <UiButton
              :loading="saving"
              :disabled="
                isMutating ||
                draftConflict ||
                propertyIssues.length > 0 ||
                actionIssues.length > 0 ||
                hasSummaryIssues ||
                (summarySources.length > 0 && (summaryCatalogLoading || !supportsQuerySummaries)) ||
                (summarySources.length > 0 && Boolean(summaryCatalogError)) ||
                (!hasUnsavedChanges && revision?.templateVersion === 4)
              "
              @click="() => void saveDraft()"
            >
              保存草稿
            </UiButton>
            <UiButton v-if="hasUnsavedChanges" :disabled="isMutating" @click="discardUnsavedChanges">
              放弃本次更改
            </UiButton>
            <UiButton
              type="primary"
              :loading="publishing"
              :disabled="
                isMutating ||
                draftConflict ||
                propertyIssues.length > 0 ||
                actionIssues.length > 0 ||
                unavailableSources.length > 0 ||
                hasSummaryIssues ||
                (summarySources.length > 0 && (summaryCatalogLoading || !supportsQuerySummaries)) ||
                (summarySources.length > 0 && Boolean(summaryCatalogError)) ||
                Boolean(draftParseError)
              "
              @click="publishDraft"
            >
              发布草稿
            </UiButton>
          </template>
        </div>
      </template>
    </ManagementPanelHeader>
    <div v-if="configuredMode !== compositionMode" class="page-composition-mode">
      <UiButton :disabled="isMutating" @click="applyConfiguredMode">采用概览中的呈现方式</UiButton>
    </div>
    <ManagementWorkspace class="page-composition-workspace__body" layout="composer" :explorer-count="2">
      <ManagementExplorerColumn collapsible :title="paletteTitle">
        <RecordExplorerPanel
          v-model:search-keyword="fieldKeyword"
          :title="paletteTitle"
          search-placeholder="搜索字段"
          :searchable="editorMode === 'fields'"
          :refresh-disabled="isMutating"
          @refresh="loadMetadataTree"
        >
          <template v-if="editorMode === 'fields'" #utility-actions>
            <label class="page-composition-system-fields-toggle">
              <span>系统字段</span>
              <UiSwitch
                v-model:checked="showSystemFields"
                size="small"
                :title="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
                :aria-label="showSystemFields ? '隐藏系统字段' : '显示系统字段'"
              />
            </label>
          </template>
          <UiSpin v-if="loading && !relation" tip="加载主实体字段" />
          <UiEmpty
            v-else-if="!relation"
            description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体"
          />
          <div v-else class="metadata-tree" data-testid="page-composer-metadata-tree">
            <UiTree
              v-model:expanded-keys="metadataExpandedKeys"
              :nodes="metadataTreeNodes"
              :load-children="loadReferenceChildren"
              :reload-key="metadataTreeReloadKey"
              :selected-key="selectedMetadataTreeKey"
              :draggable="!isMutating"
              :drag-operations="['copy']"
              :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
              :drag-payload-of="metadataDragPayload"
              :can-drag="canDragMetadataNode"
              :allow-drop="() => false"
              @select="selectMetadataNode"
              @action="addMetadataNode"
            />
            <UiEmpty v-if="editorMode === 'actions' && !moduleActions.length" description="暂无模块动作" />
            <UiEmpty v-if="editorMode === 'fields' && !visibleFields.length" description="暂无可编排字段" />
          </div>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <ManagementExplorerColumn collapsible :title="structureTitle">
        <RecordExplorerPanel
          :title="structureTitle"
          :searchable="false"
          :refresh-disabled="isMutating"
          @refresh="reloadComposition"
        >
          <div class="page-composition-structure" data-testid="page-composer-ui-tree">
            <PageCompositionTree
              :skeleton="skeleton"
              :searchable-field-ids="
                metadataFields
                  .filter((field) => searchableFields.includes(field.fieldName))
                  .map((field) => field.id)
              "
              :quick-search-fields="
                quickSearchFields.map((fieldName) => ({
                  fieldName,
                  title: metadataFields.find((field) => field.fieldName === fieldName)?.title ?? fieldName,
                }))
              "
              :query-summaries="summarySources"
              :summaries-supported="supportsQuerySummaries"
              :summary-descriptions="summaryDescriptions"
              :summary-issues="summaryTreeIssues"
              :explorer-title="
                metadataFields.find((field) => field.fieldName === explorerTitleField)?.title ??
                explorerTitleField
              "
              :explorer-secondary="
                metadataFields.find((field) => field.fieldName === explorerSecondaryField)?.title ??
                explorerSecondaryField
              "
              :list-fields="state.listFields.value"
              :form-fields="state.formFields.value"
              :form-groups="state.formGroups.value"
              :form-order="state.orderedForm.value"
              :form-relations="state.formRelations.value"
              :selected-key="selectedUiTreeKey"
              :disabled="isMutating"
              @node-action="handleNodeAction"
              @select="selectUiTreeKey"
              @double-click="handleUiTreeDoubleClick"
              @reorder-list-field="reorderListField"
              @reorder-form-field="reorderFormField"
              @move-form-field-to-group="moveFormFieldToGroup"
              @move-group-field-to-form="moveGroupFieldToForm"
              @reorder-group-field="reorderGroupField"
              @move-group-field-to-group="moveGroupFieldToGroup"
              @reorder-group="reorderGroup"
              @reorder-relation-field="reorderRelationField"
              :action-placements="actionPlacements"
              :module-actions="moduleActions"
              :editor-mode="editorMode"
              @reorder-query-summary="reorderQuerySummary"
              @source-drop="handleCompositionSourceDrop"
              @action-drop="handlePreviewActionDrop"
            />
          </div>
        </RecordExplorerPanel>
      </ManagementExplorerColumn>

      <RecordDetailPanel title="预览">
        <template #actions>
          <UiRadioGroup
            :value="effectivePreviewMode"
            :options="previewModes"
            :disabled="!previewDescriptor || isMutating"
            @update:value="selectPreviewMode"
          />
        </template>
        <div v-if="actionIssues.length" role="alert" class="page-composition-source-error">
          {{ actionIssues.join('；') }}。请修正入口后发布。
        </div>
        <div v-if="editorMode === 'actions'" class="page-composition-action-help">
          标准按钮可隐藏或排序，自定义动作可拖入对应区域。
        </div>
        <UiRadioGroup
          v-if="editorMode === 'actions' && state.previewMode.value === 'edit'"
          v-model:value="actionFormMode"
          :options="[
            { value: 'create', label: '新建状态' },
            { value: 'edit', label: '编辑状态' },
          ]"
        />
        <div v-if="propertyIssues.length" role="alert" class="page-composition-source-error">
          列宽格式有误，请修正后保存：
          <UiButton
            v-for="field in propertyIssues"
            :key="field.id"
            size="small"
            @click="handleNodeAction('configure', `ui:field:list:${field.id}`)"
          >
            {{ fieldDisplayTitle(field) }}
          </UiButton>
        </div>
        <div v-if="draftConflict" class="page-composition-conflict" role="alert">
          <span>草稿已被其他会话更新，本地修改已保留。请加载最新草稿后继续编辑。</span>
          <UiButton :disabled="isMutating" @click="reloadComposition">加载最新草稿</UiButton>
        </div>
        <div
          v-if="summarySources.length && !supportsQuerySummaries"
          class="page-composition-source-error"
          role="alert"
        >
          汇总统计仅支持列表卡片布局；当前配置已保留，切回列表卡片后可继续编辑。
          <UiButton size="small" :disabled="isMutating" @click="compositionMode = 'LIST_CARD'"
            >切回列表卡片</UiButton
          >
          <UiButton size="small" :disabled="isMutating" @click="state.replaceQuerySummaries([])"
            >清空汇总</UiButton
          >
        </div>
        <div v-if="hasSummaryIssues" class="page-composition-source-error" role="alert">
          <span>汇总配置需修正：</span>
          <UiButton
            v-for="entry in summaryIssueEntries"
            :key="entry.key"
            type="link"
            :disabled="isMutating"
            @click="openSummaryEditor(entry.key)"
          >
            {{ entry.title }}：{{ entry.messages.join('、') }}
          </UiButton>
        </div>
        <p
          v-if="
            unavailableSources.length || draftParseError || (summarySources.length && summaryCatalogError)
          "
          class="page-composition-source-error"
          role="alert"
        >
          {{
            draftParseError ??
            (summarySources.length ? summaryCatalogError : undefined) ??
            `来源失效：${[...new Set(unavailableSources)].join('、')}。配置已保留，请在编排树中移除标记节点并重新选择；修正后才能发布。`
          }}
        </p>
        <div
          v-if="previewError"
          class="page-composition-preview-status page-composition-preview-status--error"
          aria-live="polite"
        >
          <span>草稿解析失败：{{ previewError }}</span>
          <span v-if="previewDescriptor">当前展示的是上一次成功解析结果，不代表当前草稿。</span>
          <UiButton size="small" :disabled="previewLoading" @click="retryPreviewDescriptor">
            重新解析
          </UiButton>
        </div>
        <PageCompositionDescriptorPreview
          v-if="previewDescriptor"
          :descriptor="previewDescriptor"
          :module-alias="props.moduleAlias"
          :mode="effectivePreviewMode"
          :selected-field-name="selectedPreviewFieldName"
          :accept-external-drop="true"
          :placement-disabled="isMutating || previewLoading || Boolean(previewError)"
          :placement-commit-failed="Boolean(previewError)"
          :structure="previewStructure"
          :action-form-mode="actionFormMode"
          :action-placements="actionPlacements"
          :module-actions="moduleActions"
          :query-summaries="supportsQuerySummaries ? summarySources : []"
          @select-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName)"
          @configure-field="(slot, fieldName) => selectDescriptorPreviewField(slot, fieldName, true)"
          @configure-relation-field="configurePreviewRelationField"
          @configure-action="(anchor, code) => handleUiTreeDoubleClick(`ui:action:${anchor}:${code}`)"
          @configure-summaries="openSummaryEditor"
          @placement-drop="handlePreviewPlacement"
          @action-drop="(source, target) => handlePreviewActionDrop(source, target, true)"
        />
        <UiEmpty
          v-else-if="revision && !previewLoading && !previewError"
          class="page-composition-preview-empty"
          :description="
            previewError
              ? '当前草稿未能解析；可重新解析，或修正页面结构后自动重试。'
              : '正在等待草稿解析结果。'
          "
        />
        <UiEmpty
          v-else-if="!revision"
          class="page-composition-preview-empty"
          description="初始化页面草稿后，即可查看页面预览。"
        />
      </RecordDetailPanel>

      <RecordDetailDrawer
        :open="propertyDrawerOpen"
        render-mode="inline"
        :title="propertyDrawerTitle"
        :width="420"
        @close="propertyDrawerOpen = false"
      >
        <div v-if="selectedQuickSearch" class="component-property-drawer">
          <label>
            <span>搜索占位提示</span>
            <UiInput
              :value="quickSearchPlaceholderDraft"
              :disabled="isMutating"
              placeholder="例如：搜索名称、编码或 ID"
              @update:value="updateQuickSearch"
            />
          </label>
        </div>
        <div v-if="selectedActionEntry" class="component-property-drawer">
          <label
            ><span>按钮标题</span
            ><UiInput
              :value="selectedActionEntry.title ?? ''"
              :placeholder="pageActionEntryTitle({ ...selectedActionEntry, title: undefined })"
              @update:value="
                (value) => {
                  if (selectedActionEntry)
                    actionButtonMembers(actionPlacements, selectedActionEntry).forEach((entry) => {
                      entry.title = value.trim() || undefined;
                    });
                }
              "
          /></label>
          <p>业务动作：{{ selectedActionEntry.actionCode }}</p>
          <p>交互：{{ pageActionEntryDescription(selectedActionEntry) }}</p>
        </div>
        <div v-else-if="selectedField" class="component-property-drawer">
          <label>
            <span>展示标题</span>
            <UiInput
              :value="propertyDraft.label"
              :disabled="isMutating"
              :placeholder="selectedField.title"
              @update:value="updateFieldProperty('label', $event)"
            />
          </label>
          <template v-if="state.selectedNode.value?.slot === 'list' || selectedRelationField">
            <label>
              <span>列宽</span>
              <span v-if="selectedRelationField" class="component-property-drawer__width-input">
                <UiInput
                  :value="propertyDraft.width?.replace(/px$/, '')"
                  type="number"
                  :step="1"
                  :disabled="isMutating"
                  placeholder="默认 160"
                  @update:value="
                    updateFieldProperty('width', $event.trim() ? `${$event.trim()}px` : undefined)
                  "
                />
                <span>px</span>
              </span>
              <UiInput
                v-else
                :value="propertyDraft.width"
                :disabled="isMutating"
                placeholder="例如 160px 或 25%"
                @update:value="updateFieldProperty('width', $event)"
              />
              <small v-if="propertyValidationMessage" class="component-property-drawer__error">
                {{ propertyValidationMessage }}
              </small>
            </label>
            <label>
              <span>对齐</span>
              <UiSelect
                :value="propertyDraft.align"
                :disabled="isMutating"
                :options="[
                  { label: '左对齐', value: 'left' },
                  { label: '居中', value: 'center' },
                  { label: '右对齐', value: 'right' },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty(
                    'align',
                    $event === 'left' || $event === 'center' || $event === 'right' ? $event : undefined,
                  )
                "
              />
            </label>
          </template>
          <template v-else>
            <label>
              <span>表单列宽度</span>
              <UiSelect
                :value="propertyDraft.columnSpan"
                :disabled="isMutating"
                :options="[
                  { label: '半行（1 列）', value: 1 },
                  { label: '整行（2 列）', value: 2 },
                ]"
                placeholder="遵循平台默认"
                @update:value="
                  updateFieldProperty('columnSpan', $event === 1 || $event === 2 ? $event : undefined)
                "
              />
            </label>
            <label class="component-property-drawer__switch">
              <span>只读展示</span>
              <UiSwitch
                :checked="selectedField.platformReadOnly || propertyDraft.readOnly"
                :disabled="isMutating || selectedField.platformReadOnly"
                @update:checked="updateFieldProperty('readOnly', $event)"
              />
            </label>
          </template>
        </div>
        <div v-else-if="selectedGroup" class="component-property-drawer">
          <label>
            <span>分组标题</span>
            <UiInput
              :value="groupTitleDraft"
              :disabled="isMutating"
              placeholder="例如：基本信息"
              @update:value="updateGroup($event, groupSubtitleDraft)"
            />
            <small v-if="!groupTitleDraft.trim()" class="component-property-drawer__error"
              >标题不能为空，当前保留原名称。</small
            >
          </label>
          <label>
            <span>辅助说明</span>
            <UiInput
              :value="groupSubtitleDraft"
              :disabled="isMutating"
              placeholder="可选，例如：填写考试基础资料"
              @update:value="updateGroup(groupTitleDraft, $event)"
            />
          </label>
        </div>
      </RecordDetailDrawer>
      <RecordDetailDrawer
        :open="summaryDrawerOpen"
        render-mode="inline"
        title="汇总统计"
        :width="480"
        @close="closeSummaryEditor"
      >
        <PageQuerySummaryEditor
          :summaries="summarySources"
          :catalog="summaryCatalog"
          :loading="summaryCatalogLoading"
          :error="summaryCatalogError"
          :disabled="isMutating"
          :selected-key="selectedSummaryKey"
          :focus-request="summaryFocusRequest"
          :issues="summaryEditorIssues"
          :descriptions="summaryDescriptions"
          @add="addQuerySummary"
          @update="updateQuerySummary"
          @remove="removeQuerySummary"
          @move="moveQuerySummary"
          @retry="loadSummaryCatalog(true)"
        />
      </RecordDetailDrawer>
    </ManagementWorkspace>
  </section>
</template>

<style scoped>
.page-composition-mode {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--muyun-text-muted);
}
.page-composition-mode :deep(.ant-select) {
  min-width: 140px;
}

.page-composition-system-fields-toggle {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.page-composition-conflict {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--muyun-danger-base);
}
.page-composition-source-error {
  color: var(--muyun-danger-base);
}
.page-composition-workspace {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  gap: 10px;
}
.page-composition-workspace__body {
  flex: 1 1 auto;
  min-height: 0;
}
.page-composition-workspace > :deep(.management-panel-header) {
  flex: 0 0 auto;
}

.metadata-tree,
.page-composition-structure {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: hidden;
}
.metadata-tree > :deep(.ui-tree) {
  flex: 1 1 auto;
}
.metadata-tree :deep(.ant-tree),
.page-composition-structure :deep(.ant-tree) {
  flex: 0 0 auto;
  min-height: 0;
  overflow: visible;
}
/* Keep node names legible beside stable inline action slots in the compact composer. */
.metadata-tree :deep(.ant-tree-indent-unit),
.page-composition-structure :deep(.ant-tree-indent-unit) {
  width: 16px;
}
.metadata-tree :deep(.ui-record-explorer-item-title),
.page-composition-structure :deep(.ui-record-explorer-item-title) {
  flex-shrink: 0;
}
.metadata-tree :deep(.ui-record-explorer-item-secondary),
.page-composition-structure :deep(.ui-record-explorer-item-secondary) {
  flex-shrink: 4;
}
.page-composition-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.page-composition-preview-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 10px 0 0;
  color: var(--muyun-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.page-composition-preview-status--error {
  color: var(--muyun-danger-base);
}
.page-composition-preview-empty {
  min-height: 280px;
  margin-top: 12px;
  border: 1px solid var(--muyun-border);
  border-radius: 8px;
}
.component-property-drawer {
  display: grid;
  gap: 16px;
}
.component-property-drawer label {
  display: grid;
  gap: 6px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.component-property-drawer__switch {
  grid-template-columns: 1fr auto;
  align-items: center;
}
.component-property-drawer p {
  margin: 0;
  color: var(--muyun-text-muted);
  font-size: 13px;
  line-height: 1.55;
}
.component-property-drawer__error {
  color: var(--muyun-danger-base);
  font-size: 12px;
  line-height: 1.4;
}
.component-property-drawer__width-input {
  display: flex;
  align-items: center;
  gap: 8px;
}
.component-property-drawer__width-input > :first-child {
  flex: 1;
  min-width: 0;
}
</style>
