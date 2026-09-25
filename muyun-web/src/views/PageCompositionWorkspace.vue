<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, watch } from 'vue';
import {
  ManagementExplorerColumn,
  ManagementPanelHeader,
  ManagementWorkspace,
  RecordDetailDrawer,
  RecordDetailPanel,
  RecordExplorerPanel,
  ManagementTabs,
  presentPlatformError,
  presentPlatformSuccess,
} from '@muyun/platform-components';
import { loadOptionFieldItems } from '@/platform-components/optionFieldOptionCache';
import { hasOptionHierarchy } from '@/platform-components/optionFieldOptions';
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
  UiTree,
  UiEmpty,
  UiInput,
  UiSelect,
  UiSwitch,
  UiRadioGroup,
  type UiRadioOption,
  type UiRecordInlineAction,
  type UiTreeLoadRequest,
  type UiTreeLoadResult,
  type UiTreeNode,
} from '@muyun/vue-ui-antdv';
import type {
  MetadataField,
  ModuleMetadataRelation,
  OptionItemDescriptor,
  ResolvedModuleUiDescriptor,
  WebPageResponse,
  WebQueryCondition,
} from '@muyun/web-contracts';
import {
  createPageCompositionDraftState,
  orderedFormItems,
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
import MetadataSourceTree from './MetadataSourceTree.vue';
import { metadataSourceFieldNode, metadataSourceRoot } from './metadataSourceTree';
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
import {
  dictionaryRadioEligibilityIssue,
  type DictionaryRadioCandidateFacts,
} from './dictionaryRadioEligibility';
import { createDictionaryRadioFactRequestEpoch } from './dictionaryRadioFactRequestEpoch';

import {
  type ComponentCatalog,
  sameTitleFields,
  componentField,
  componentFieldForSave,
  componentFieldDefinition,
  type PendingComponentField,
} from './pageCompositionComponents';

import { usePageCompositionComponents } from './usePageCompositionComponents';

defineOptions({ name: 'PageCompositionWorkspace' });

const props = defineProps<{ moduleAlias: string; moduleTitle?: string }>();
const moduleContext = useModuleContext({ moduleAlias: 'platform.module' });
const state = createPageCompositionDraftState();

const paletteMode = ref('fields');
const componentSession = usePageCompositionComponents(state, () => currentUiTreeJson.value);
const {
  fieldUsageIndex,
  pendingComponents,
  pendingChildren,
  pendingFieldSources,
  activeComponents,
  activeChildren,
  componentNameInvalid,
  childInvalid,
  pendingSearchableFields,
} = componentSession;

const componentCatalog = ref<ComponentCatalog>();
const componentCatalogLoading = ref(false);
let componentCatalogSequence = 0;
const componentNodes = computed<UiTreeNode[]>(() => [
  ...(componentCatalog.value?.components ?? []).map((item) => ({
    key: item.component,
    title: item.title,
    isLeaf: true,
  })),
  ...(componentCatalog.value?.canCreateChild ? [{ key: 'child', title: '明细表', isLeaf: true }] : []),
]);
const selectedPendingComponent = computed(() =>
  [...pendingComponents.value, ...pendingChildren.value.flatMap((child) => child.fields)].find(
    (item) => item.key === selectedField.value?.id,
  ),
);

const selectedFieldUsage = computed(() =>
  selectedRelationField.value && selectedRelation.value
    ? [selectedRelation.value.title]
    : selectedField.value
      ? (fieldUsageIndex.value.get(selectedField.value.fieldName) ?? [])
      : [],
);
const duplicateComponentFields = computed(() =>
  selectedPendingComponent.value && !selectedRelationField.value
    ? sameTitleFields(selectedPendingComponent.value.title, selectedPendingComponent.value.key, [
        ...metadataFields.value,
        ...pendingFieldSources.value,
      ])
    : [],
);

function updateComponentRequired(required: boolean) {
  const input = selectedPendingComponent.value;
  if (!input || isMutating.value) return;
  componentSession.updateField(input.key, { required });
  schedulePreviewDescriptor();
}

async function loadComponentCatalog() {
  const revisionId = revision.value?.id;
  if (!revisionId) return;
  const sequence = workspaceLoadSequence;
  const request = ++componentCatalogSequence;
  const current = () =>
    sequence === workspaceLoadSequence &&
    request === componentCatalogSequence &&
    revisionId === revision.value?.id;
  componentCatalogLoading.value = true;
  try {
    const catalog = await moduleContext.http.request<ComponentCatalog>({
      method: 'GET',
      path: `/platform.presentation_publish/revisions/${encodeURIComponent(revisionId)}/component-catalog`,
    });
    if (current()) componentCatalog.value = catalog;
  } catch (cause) {
    if (current()) {
      componentCatalog.value = undefined;
      presentPlatformError(cause, { source: 'page-composition', phase: 'load' });
    }
  } finally {
    if (request === componentCatalogSequence) componentCatalogLoading.value = false;
  }
}
watch(paletteMode, (mode) => {
  if (mode === 'components') void loadComponentCatalog();
});

function updateComponentTitle(title: string) {
  const input = selectedPendingComponent.value;
  if (!input || isMutating.value) return;
  componentSession.updateField(input.key, { title });
  schedulePreviewDescriptor();
}

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
const dictionaryRadioFacts = ref<Record<string, DictionaryRadioCandidateFacts | undefined>>({});
const dictionaryRadioFactErrors = ref<Record<string, string | undefined>>({});
const dictionaryRadioFactLoading = ref(new Set<string>());
const dictionaryRadioMaxOptions = ref(12);
const dictionaryRadioFactRequestEpoch = createDictionaryRadioFactRequestEpoch();
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
const persistedSearchableFields = ref<string[]>([]);
const searchableFields = computed(() => [
  ...persistedSearchableFields.value,
  ...pendingSearchableFields.value,
]);
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
const savedDraftApplied = ref(false);
const previewDescriptor = ref<ResolvedModuleUiDescriptor>();
const previewLayouts = ref<Record<'form' | 'detail', PageCompositionStructure>>();
const previewStructure = computed(
  () =>
    previewLayouts.value?.[
      state.separateDetail.value && effectivePreviewMode.value === 'detail' ? 'detail' : 'form'
    ],
);

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
  optionSourceType?: string;
  optionSelectionMode?: 'SINGLE' | 'MULTIPLE';
  expandable?: boolean;
  readOnly?: boolean;
  systemManaged?: boolean;
}

const platformDefaultReferencePickerAlias = '__platform_default_reference_picker__';
const standardReferencePickerAliases = new Set(['record_picker_dropdown', 'record_picker_dialog']);
const platformDefaultDictionaryPresentationAlias = '__platform_default_dictionary_presentation__';
const standardDictionaryPresentationAliases = new Set([
  'dictionary_dropdown',
  'dictionary_multi_dropdown',
  'dictionary_dialog',
  'dictionary_multi_dialog',
  'dictionary_radio',
]);
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
const hasCatalogDependentSummary = computed(() =>
  summarySources.value.some((summary) => ['SUM', 'CONTRIBUTOR', 'GROUPED'].includes(summary.source)),
);
const summaryCatalogBlocksMutation = computed(
  () =>
    hasCatalogDependentSummary.value && (summaryCatalogLoading.value || Boolean(summaryCatalogError.value)),
);
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
  { value: 'detail', label: '详情' },
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
  const fields = filterSystemFields([...metadataFields.value, ...pendingFieldSources.value]);
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
  ...pendingFieldSources.value,
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
const selectedDirectReferenceFormField = computed(() => {
  const field = selectedField.value;
  const node = state.selectedNode.value;
  if (
    !field ||
    !node ||
    node.slot !== 'form' ||
    node.kind === 'relationField' ||
    !field.referenceModuleAlias ||
    field.fieldName.includes('.') ||
    field.platformReadOnly
  )
    return undefined;
  return field;
});
const selectedDirectDictionaryFormField = computed(() => {
  const field = selectedField.value;
  const node = state.selectedNode.value;
  if (
    !field ||
    !node ||
    node.slot !== 'form' ||
    node.kind === 'relationField' ||
    field.fieldName.includes('.') ||
    field.platformReadOnly ||
    field.optionSourceType !== 'dictionary'
  )
    return undefined;
  return field;
});
const referencePickerPresentationValue = computed(
  () => propertyDraft.value.fieldUiControlAlias ?? platformDefaultReferencePickerAlias,
);
const referencePickerPresentationOptions = computed(() => {
  const currentAlias = propertyDraft.value.fieldUiControlAlias;
  return [
    { label: '平台默认（清除配置）', value: platformDefaultReferencePickerAlias },
    { label: '下拉选择', value: 'record_picker_dropdown' },
    { label: '弹窗选择', value: 'record_picker_dialog' },
    ...(currentAlias && !standardReferencePickerAliases.has(currentAlias)
      ? [{ label: `当前自定义控件（${currentAlias}）`, value: currentAlias, disabled: true }]
      : []),
  ];
});
const dictionaryPresentationValue = computed(() => {
  const alias = propertyDraft.value.fieldUiControlAlias;
  if (!alias) return platformDefaultDictionaryPresentationAlias;
  if (alias === 'dictionary_dropdown' || alias === 'dictionary_multi_dropdown') return 'DROPDOWN';
  if (alias === 'dictionary_dialog' || alias === 'dictionary_multi_dialog') return 'DIALOG';
  if (alias === 'dictionary_radio') return 'RADIO';
  return alias;
});
const selectedDictionaryRadioIssue = computed(() => {
  const field = selectedDirectDictionaryFormField.value;
  return field ? dictionaryRadioIssueOf(field) : undefined;
});
const dictionaryPresentationOptions = computed(() => {
  const field = selectedDirectDictionaryFormField.value;
  const currentAlias = propertyDraft.value.fieldUiControlAlias;
  const radioIssue = field ? dictionaryRadioIssueOf(field) : 'radio 仅支持单值数据字典';
  return [
    { label: '平台默认（下拉）', value: platformDefaultDictionaryPresentationAlias },
    { label: '下拉选择', value: 'DROPDOWN' },
    ...(field?.optionSelectionMode === 'SINGLE'
      ? [
          {
            label: radioIssue ? `radio 单选组（不可用：${radioIssue}）` : 'radio 单选组',
            value: 'RADIO',
            disabled: Boolean(radioIssue),
          },
        ]
      : []),
    ...(currentAlias && !standardDictionaryPresentationAliases.has(currentAlias)
      ? [{ label: `当前自定义控件（${currentAlias}）`, value: currentAlias, disabled: true }]
      : []),
  ];
});
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
const hasPendingChanges = computed(() =>
  Boolean(revision.value && (!savedDraftApplied.value || hasUnsavedChanges.value)),
);
useWorkspaceViewUnsavedState('页面配置', () => hasUnsavedChanges.value);
const isMutating = computed(
  () => saving.value || publishing.value || loading.value || compositionLoading.value,
);
const unavailableNavigationSources = computed(() => {
  const known = new Set(
    [...metadataFields.value, ...pendingFieldSources.value].map((field) => field.fieldName),
  );
  const fields = [...quickSearchFields.value];
  if (skeleton.value?.columns === false) {
    fields.push(explorerTitleField.value || '导航标题（未配置）');
    if (explorerSecondaryField.value) fields.push(explorerSecondaryField.value);
  }
  return [...new Set(fields.filter((field) => !known.has(field)))];
});
const compositionFields = computed(() =>
  (state.separateDetail.value ? Object.values(state.layouts.value) : [state.layouts.value.form]).flatMap(
    (layout) => [...layout.fields, ...layout.groups.flatMap((group) => group.fields)],
  ),
);
const unavailableSources = computed(() =>
  [
    ...(skeleton.value?.columns === false ? [] : state.listFields.value),
    ...compositionFields.value,
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
const dictionaryRadioIssues = computed(() => {
  const fields = compositionFields.value;
  return fields.flatMap((field) => {
    if (field.properties?.fieldUiControlAlias !== 'dictionary_radio') return [];
    const issue = dictionaryRadioIssueOf(field);
    return issue ? [`${fieldDisplayTitle(field)}：${issue}`] : [];
  });
});
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
  const status = publishing.value
    ? '正在生效'
    : draftConflict.value
      ? '修改冲突'
      : hasUnsavedChanges.value
        ? '有未保存修改'
        : hasPendingChanges.value
          ? '待生效'
          : publishedRevision.value
            ? '已生效'
            : '尚未配置';
  return `Web · 全局 · ${status}`;
});

function sameComposition(left?: PresentationRevision, right?: PresentationRevision) {
  if (
    !left ||
    !right ||
    left.templateAlias !== right.templateAlias ||
    left.templateVersion !== right.templateVersion
  )
    return false;
  try {
    const normalize = (value: unknown): unknown =>
      Array.isArray(value)
        ? value.map(normalize)
        : value && typeof value === 'object'
          ? Object.fromEntries(
              Object.entries(value)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([key, item]) => [key, normalize(item)]),
            )
          : value;
    return (
      JSON.stringify(normalize(JSON.parse(left.uiTreeJson ?? '{}'))) ===
      JSON.stringify(normalize(JSON.parse(right.uiTreeJson ?? '{}')))
    );
  } catch {
    return false;
  }
}
const metadataTreeNodes = computed<UiTreeNode[]>(() => [
  ...(editorMode.value === 'fields'
    ? [
        metadataSourceRoot(mainEntityTitle.value, [
          ...visibleFields.value.map(metadataFieldNode),
          ...childRelationNodes(relation.value?.metadataId),
        ]),
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
watch([state.selectedNodeId, state.activeLayout], () => {
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
    invalidateDictionaryRadioFacts();
    resetPreviewDescriptor();
    relation.value = undefined;
    metadataRelations.value = [];
    metadataFields.value = [];
    dictionaryRadioMaxOptions.value = 12;
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
    state.mergeLayout('form');
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
  invalidateDictionaryRadioFacts();
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
  // Reset synchronously: otherwise a stale in-flight request can leave the new binding's
  // same-named field in the loading gate while the directory read is still pending or fails.
  invalidateDictionaryRadioFacts();
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
    persistedSearchableFields.value = profile.searchableFields ?? [];
    if (runtime) moduleActions.value = runtime.actions ?? [];
    relation.value = main;
    metadataRelations.value = relations;
    const fallbackFields = toFields(fields);
    // Preserve metadata identities and field policies while adding the directory's reference facts.
    const referenceRoot = await loadReferenceDirectory(moduleAlias, '');
    if (!current()) return;
    const referenceByName = new Map(referenceRoot.map((field) => [field.fieldName, field]));
    // A metadata edit can rebind a field to another dictionary while this KeepAlive workspace remains mounted.
    // The presentation-only facts were already reset before any async directory read started.
    metadataFields.value = fallbackFields.map((field) => {
      const reference = referenceByName.get(field.fieldName);
      return reference
        ? {
            ...field,
            referenceModuleAlias: reference.referenceModuleAlias ?? field.referenceModuleAlias,
            referenceCardinality: reference.referenceCardinality ?? field.referenceCardinality,
            optionSourceType: reference.optionSourceType ?? field.optionSourceType,
            optionSelectionMode: reference.optionSelectionMode ?? field.optionSelectionMode,
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
    if (componentCatalog.value && !publishing.value) await loadComponentCatalog();
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
    optionSourceType: field.optionSourceType,
    optionSelectionMode: field.optionSelectionMode,
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
    .request<{
      moduleAlias: string;
      path?: string;
      dictionaryRadioMaxOptions?: number;
      fields?: PageReferenceField[];
    }>({
      method: 'GET',
      path: `/platform.module/${encodeURIComponent(moduleAlias)}/page-reference-fields${
        path ? `?path=${encodeURIComponent(path)}` : ''
      }`,
    })
    .then((response) => {
      if (
        epoch === referenceDirectoryEpoch &&
        moduleAlias === props.moduleAlias &&
        !path &&
        Number.isSafeInteger(response.dictionaryRadioMaxOptions) &&
        response.dictionaryRadioMaxOptions! > 0
      )
        dictionaryRadioMaxOptions.value = response.dictionaryRadioMaxOptions!;
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
  return metadataSourceFieldNode(field, {
    secondary:
      [
        ...(fieldUsageIndex.value.get(field.fieldName) ?? []),
        field.pending ? '新增' : metadataSourceFieldNode(field, { key: field.id }).secondary,
      ]
        .filter(Boolean)
        .join(' · ') || undefined,
    key: `metadata:field:${field.id}`,
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
          ...(state.separateDetail.value ? [{ key: 'add-detail', title: '添加到详情' }] : []),
          { key: 'add-form', title: state.separateDetail.value ? '添加到表单' : '添加到详情 / 表单' },
        ],
      },
    ],
  });
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
    componentSession.reset();
    componentCatalogSequence += 1;
    componentCatalogLoading.value = false;
    componentCatalog.value = undefined;
    resetPreviewDescriptor();
    page.value = nextPage;
    variant.value = nextVariant;
    revision.value = latestRevision(drafts);
    publishedRevision.value = latestRevision(published);
    savedDraftApplied.value = sameComposition(revision.value, publishedRevision.value);
    draftConflict.value = false;
    removedDraft.value = undefined;
    draftParseError.value = undefined;
    state.mergeLayout('form');
    state.replaceFields({ list: [], form: [] });
    state.updateQuickSearchPlaceholder(undefined);
    savedUiTreeJson.value = undefined;
    await hydrateDraft(revision.value);
    propertyDrawerOpen.value = false;
    if (paletteMode.value === 'components') await loadComponentCatalog();
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
      title: '加载最新配置',
      content: '加载成功后将替换当前编排，并放弃尚未保存的本地修改。是否继续？',
      okText: '加载最新配置',
    });
    if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  }
  await loadComposition();
}

/** Clears stale radio preflight state before a metadata directory can expose a replacement binding. */
function invalidateDictionaryRadioFacts() {
  dictionaryRadioFactRequestEpoch.invalidate();
  dictionaryRadioFacts.value = {};
  dictionaryRadioFactErrors.value = {};
  dictionaryRadioFactLoading.value = new Set();
}

function resetPreviewDescriptor() {
  previewRequestSequence += 1;
  if (previewDebounceTimer) {
    clearTimeout(previewDebounceTimer);
    previewDebounceTimer = undefined;
  }
  previewDescriptor.value = undefined;
  previewLayouts.value = undefined;
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
  if (
    unavailableSources.value.length ||
    draftParseError.value ||
    propertyIssues.value.length ||
    dictionaryRadioIssues.value.length
  ) {
    previewLoading.value = false;
    // This incompatibility is already presented as a page-composition alert.
    // Keeping the preview error empty avoids reporting the same actionable
    // issue twice while save and publish remain blocked below.
    if (dictionaryRadioIssues.value.length) {
      previewError.value = undefined;
      return;
    }
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
      body: {
        uiTreeJson,
        ...(activeComponents.value.length
          ? { newFields: activeComponents.value.map(componentFieldDefinition) }
          : {}),
        ...(activeChildren.value.length
          ? {
              newChildren: activeChildren.value.map((child) => ({
                ...child,
                fields: child.fields.map(componentFieldDefinition),
              })),
            }
          : {}),
      },
    });
    if (requestSequence !== previewRequestSequence) return;
    if (uiTreeJson !== currentUiTreeJson.value) return;
    const structureFor = (layout: 'form' | 'detail'): PageCompositionStructure => {
      const source = state.layouts.value[layout];
      return {
        list: state.listFields.value,
        form: source.fields,
        groups: source.groups,
        order: orderedFormItems(source.fields, source.groups, source.order),
        relations: state.formRelations.value,
      };
    };
    previewLayouts.value = { form: structureFor('form'), detail: structureFor('detail') };
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
        slot?: PageComposerSlot | 'detail';
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
    const resolve = (slot: PageComposerSlot | 'detail') =>
      tree.nodes?.find((node) => node.slot === slot)?.fields ?? [];
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
    const list = resolve('list')
      .map((entry) => resolveField(entry, false))
      .filter((field): field is PageComposerField => Boolean(field));
    const relations = (tree.nodes?.find((node) => node.slot === 'form')?.relations ?? []).flatMap((entry) => {
      const relation = metadataRelations.value.find(
        (candidate) => candidate.relationAlias === entry.relation,
      );
      const pendingChild = pendingChildren.value.find((child) => `detail_${child.key}` === entry.relation);
      const relationCode = relation?.relationAlias ?? entry.relation;
      if (!relationCode) return [];
      return [
        {
          id: relation?.id ?? pendingChild?.key ?? relationCode,
          relationCode,
          unavailable: !relation && !pendingChild,
          pending: !relation && !!pendingChild,
          title: entry.title?.trim() || relation?.title || relation?.relationAlias || relationCode,
          fields: (entry.fields ?? []).flatMap((entryField) => {
            const fieldName = typeof entryField === 'string' ? entryField : entryField.field;
            const properties = typeof entryField === 'string' ? undefined : entryField.props;
            const childField = relation
              ? childMetadataFields.value
                  .get(relation.id ?? relation.metadataId ?? '')
                  ?.find((candidate) => candidate.fieldName === fieldName)
              : pendingChild?.fields.map(componentField).find((field) => field.fieldName === fieldName);
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
    });
    const previousLayout = state.activeLayout.value;
    mergeLayoutOpen.value = false;
    state.mergeLayout('form');
    state.separateDetail.value = tree.nodes?.some((node) => node.slot === 'detail') ?? false;
    for (const layout of (state.separateDetail.value ? ['detail', 'form'] : ['form']) as Array<
      'form' | 'detail'
    >) {
      state.selectLayout(layout);
      state.replaceFields({
        list,
        order: tree.nodes
          ?.find((node) => node.slot === layout)
          ?.order?.flatMap<PageComposerFormItem>((item) =>
            item.field
              ? [{ kind: 'field' as const, id: resolveField(item.field)!.id }]
              : item.group
                ? [{ kind: 'group' as const, id: item.group }]
                : [],
          ),
        form: resolve(layout)
          .map((entry) => resolveField(entry, true))
          .filter((field): field is PageComposerField => Boolean(field)),
        relations,
        groups: (tree.nodes?.find((node) => node.slot === layout)?.groups ?? []).flatMap((entry) => {
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
    }
    state.selectLayout(state.separateDetail.value ? previousLayout : 'form');
    customSummaryKeys.clear();
    state.replaceQuerySummaries(Array.isArray(tree.querySummaries) ? tree.querySummaries : []);
    if (hasCatalogDependentSummary.value) void loadSummaryCatalog();
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
    if ((modeTree.templateVersion ?? 1) >= 4)
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
        templateVersion: JSON.parse(treeJsonToPersist).templateVersion,
        uiTreeJson: treeJsonToPersist,
        status: pageCompositionTransport.draftRevision,
        title: latestPublished ? `基于 v${latestPublished.revisionNo ?? 1} 的草稿` : '初始草稿',
        enabled: true,
      })
    ).record;
    if (!current()) return;
    revision.value = createdRevision;
    savedDraftApplied.value = sameComposition(createdRevision, latestPublished);
    if (latestPublished) publishedRevision.value = latestPublished;
    await hydrateDraft(revision.value);
  } catch (cause) {
    if (!current()) return;
    presentPlatformError(cause, { source: 'page-composition', phase: 'action' });
  } finally {
    if (current()) saving.value = false;
  }
}

async function saveAndApply() {
  if (hasCatalogDependentSummary.value && !summaryCatalog.value && !summaryCatalogLoading.value)
    await loadSummaryCatalog();
  if (
    isMutating.value ||
    !hasPendingChanges.value ||
    componentNameInvalid.value ||
    childInvalid.value ||
    propertyIssues.value.length > 0 ||
    dictionaryRadioIssues.value.length > 0 ||
    actionIssues.value.length > 0 ||
    draftConflict.value ||
    unavailableSources.value.length ||
    hasSummaryIssues.value ||
    summaryCatalogBlocksMutation.value ||
    (summarySources.value.length > 0 && !supportsQuerySummaries.value) ||
    draftParseError.value ||
    !revision.value?.id
  )
    return;
  const sequence = workspaceLoadSequence;
  const current = () => sequence === workspaceLoadSequence;
  const variantId = variant.value?.id;
  if (!variantId) return;
  let treeJsonToPublish = currentUiTreeJson.value;
  publishing.value = true;
  try {
    let publicationCandidate: PresentationRevision = {
      ...revision.value,
      templateVersion: JSON.parse(treeJsonToPublish).templateVersion,
      uiTreeJson: treeJsonToPublish,
    };
    if (activeComponents.value.length || activeChildren.value.length) {
      if (!componentCatalog.value) throw new Error('请重新打开组件库后保存');
      publicationCandidate = await moduleContext.http.request<PresentationRevision>({
        method: 'POST',
        path: `/platform.presentation_publish/revisions/${encodeURIComponent(publicationCandidate.id!)}/save-composition`,
        body: {
          revision: publicationCandidate,
          relationId: componentCatalog.value.relationId,
          expectedMetadataVersion: componentCatalog.value.metadataVersion,
          newFields: activeComponents.value.map(componentFieldForSave),
          newChildren: activeChildren.value.map((child) => ({
            ...child,
            fields: child.fields.map(componentFieldForSave),
          })),
        },
      });
      treeJsonToPublish = publicationCandidate.uiTreeJson!;
    } else {
      await moduleContext.http.request<number>({
        method: 'POST',
        path: `/platform.presentation_publish/revisions/${encodeURIComponent(publicationCandidate.id!)}/publish`,
        body: publicationCandidate,
      });
    }
    if (!current()) return;
    // A successful publication makes this revision immutable, even if the following read fails.
    publishedRevision.value = {
      ...publicationCandidate,
      status: pageCompositionTransport.publishedRevision,
    };
    revision.value = undefined;
    savedDraftApplied.value = true;
    savedUiTreeJson.value = treeJsonToPublish;
    presentPlatformSuccess('页面已保存并生效', { source: 'page-composition', phase: 'action' });
    removedDraft.value = undefined;
    propertyDrawerOpen.value = false;
    try {
      if (pendingComponents.value.length || pendingChildren.value.length) {
        await loadMetadataTree();
        if (!current()) return;
        componentSession.reset();
        componentCatalog.value = undefined;
      }
      const nextDraft = await createFollowUpDraft(variantId, publicationCandidate, treeJsonToPublish);
      if (current()) {
        revision.value = nextDraft;
        savedDraftApplied.value = true;
        await hydrateDraft(nextDraft);
      }
    } catch {
      if (!current()) return;
      await loadComposition();
      if (!current()) return;
      presentPlatformError(new Error('页面已生效，但编辑准备失败；请点击“继续编辑”重试。'), {
        source: 'page-composition',
        phase: 'action',
      });
      return;
    }
    if (current()) await loadComposition();
  } catch (cause) {
    if (!current()) return;
    if (normalizeError(cause).code === platformErrorCodes.conflictVersion) draftConflict.value = true;
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
    content: '将放弃本次尚未保存的修改，恢复到打开编辑时的内容。是否继续？',
    okText: '放弃更改',
  });
  if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value) return;
  await hydrateDraft(revision.value);
  componentSession.reset();
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
  return slot === 'list'
    ? '列表'
    : state.separateDetail.value
      ? state.activeLayout.value === 'detail'
        ? '详情'
        : '表单'
      : '详情 / 表单';
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
  state.selectLayout(action.key === 'add-detail' ? 'detail' : 'form');
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
  if (action.key === 'add-detail') state.previewMode.value = 'detail';
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
  if (source.kind === 'child') {
    if (!componentCatalog.value?.canCreateChild || isMutating.value || target.kind !== 'form') return;
    const key = crypto.randomUUID().replaceAll('-', '');
    pendingChildren.value.push({ key, title: '明细表', fields: [] });
    state.addFormRelation({
      pending: true,
      id: key,
      relationCode: `detail_${key}`,
      title: '明细表',
      fields: [],
    });
    if (target.index !== undefined) state.moveFormRelation(key, target.index);
    openPropertyDrawer();
    return;
  }
  if (source.kind === 'component') {
    const definition = componentCatalog.value?.components.find((item) => item.component === source.component);
    if (!definition) return;
    if (target.kind === 'relation') {
      const child = pendingChildren.value.find((item) => item.key === target.relationId);
      const relation = state.formRelations.value.find((item) => item.id === target.relationId);
      if (!child || !relation || !componentCatalog.value || isMutating.value) return;
      const input: PendingComponentField = {
        key: crypto.randomUUID().replaceAll('-', ''),
        component: source.component,
        title: definition.title,
        fieldSpecAlias: definition.fieldSpecAlias,
      };
      child.fields.push(input);
      state.addFormRelationField(relation, componentField(input));
      if (target.index !== undefined) state.moveFormRelationField(relation.id, input.key, target.index);
      openPropertyDrawer();
      return;
    }
    if (!componentCatalog.value || isMutating.value || !['list', 'form', 'group'].includes(target.kind))
      return;
    const input: PendingComponentField = {
      key: crypto.randomUUID().replaceAll('-', ''),
      component: source.component,
      title: definition.title,
      fieldSpecAlias: definition.fieldSpecAlias,
    };
    pendingComponents.value.push(input);
    const field = componentField(input);
    if (target.kind === 'group') placeMetadataFieldInGroup(field, target.groupId, target.index);
    else if (target.kind === 'list' || target.kind === 'form')
      state.addField(field, target.kind, target.index);
    openPropertyDrawer();
    return;
  }
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
  state.selectLayout(effectivePreviewMode.value === 'detail' ? 'detail' : 'form');
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
  if (source.kind === 'component') {
    if (source.component.kind === 'child')
      handleCompositionSourceDrop({ kind: 'form', index }, source.component);
    else if (
      container.kind === 'list' ||
      container.kind === 'form' ||
      container.kind === 'group' ||
      container.kind === 'relation'
    )
      handleCompositionSourceDrop({ ...container, index }, source.component);
    state.previewMode.value = mode;
    return;
  }
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
  if (node.slot === 'form' && state.separateDetail.value)
    state.previewMode.value = state.activeLayout.value === 'detail' ? 'detail' : 'edit';
}

function selectDescriptorPreviewField(slot: PageComposerSlot, fieldName: string, configure = false) {
  if (isMutating.value) return;
  state.selectLayout(effectivePreviewMode.value === 'detail' ? 'detail' : 'form');
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
  state.selectLayout(key === 'detail' ? 'detail' : 'form');
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
function updateReferencePickerPresentation(value: unknown) {
  if (value !== null && value !== undefined && typeof value !== 'string') return;
  const alias = typeof value === 'string' ? value : undefined;
  if (alias && alias !== platformDefaultReferencePickerAlias && !standardReferencePickerAliases.has(alias))
    return;
  updateFieldProperty(
    'fieldUiControlAlias',
    alias === platformDefaultReferencePickerAlias ? undefined : alias,
  );
}
function updateDictionaryPresentation(value: unknown) {
  if (typeof value !== 'string') return;
  const field = selectedDirectDictionaryFormField.value;
  if (!field) return;
  if (value === 'RADIO' && dictionaryRadioIssueOf(field)) return;
  const alias =
    value === platformDefaultDictionaryPresentationAlias
      ? undefined
      : value === 'DROPDOWN'
        ? field.optionSelectionMode === 'MULTIPLE'
          ? 'dictionary_multi_dropdown'
          : 'dictionary_dropdown'
        : value === 'DIALOG'
          ? field.optionSelectionMode === 'MULTIPLE'
            ? 'dictionary_multi_dialog'
            : 'dictionary_dialog'
          : value === 'RADIO' && field.optionSelectionMode === 'SINGLE'
            ? 'dictionary_radio'
            : undefined;
  if (value !== platformDefaultDictionaryPresentationAlias && !alias) return;
  updateFieldProperty('fieldUiControlAlias', alias);
}

function dictionaryRadioIssueOf(field: PageComposerField): string | undefined {
  return dictionaryRadioEligibilityIssue({
    optionSourceType: field.optionSourceType,
    optionSelectionMode: field.optionSelectionMode,
    facts: dictionaryRadioFacts.value[field.fieldName],
    loadError: dictionaryRadioFactErrors.value[field.fieldName],
    maxOptions: dictionaryRadioMaxOptions.value,
  });
}

async function loadDictionaryRadioFacts(field: PageComposerField) {
  if (field.optionSourceType !== 'dictionary' || field.optionSelectionMode !== 'SINGLE') return;
  const fieldName = field.fieldName;
  if (dictionaryRadioFacts.value[fieldName] || dictionaryRadioFactLoading.value.has(fieldName)) return;
  const requestGeneration = dictionaryRadioFactRequestEpoch.capture();
  dictionaryRadioFactLoading.value = new Set(dictionaryRadioFactLoading.value).add(fieldName);
  try {
    const items = await loadOptionFieldItems(moduleContext, fieldName, undefined, props.moduleAlias, true);
    if (!dictionaryRadioFactRequestEpoch.isCurrent(requestGeneration)) return;
    const facts: DictionaryRadioCandidateFacts = {
      enabledCandidateCount: items.filter((item: OptionItemDescriptor) => item.enabled).length,
      hasHierarchy: hasOptionHierarchy(items),
    };
    dictionaryRadioFacts.value = { ...dictionaryRadioFacts.value, [fieldName]: facts };
    if (dictionaryRadioFactErrors.value[fieldName]) {
      const nextErrors = { ...dictionaryRadioFactErrors.value };
      delete nextErrors[fieldName];
      dictionaryRadioFactErrors.value = nextErrors;
    }
  } catch (cause) {
    if (!dictionaryRadioFactRequestEpoch.isCurrent(requestGeneration)) return;
    dictionaryRadioFactErrors.value = {
      ...dictionaryRadioFactErrors.value,
      [fieldName]: cause instanceof Error ? cause.message : '请求失败',
    };
  } finally {
    if (dictionaryRadioFactRequestEpoch.isCurrent(requestGeneration)) {
      const nextLoading = new Set(dictionaryRadioFactLoading.value);
      nextLoading.delete(fieldName);
      dictionaryRadioFactLoading.value = nextLoading;
    }
  }
}

watch(
  () => selectedDirectDictionaryFormField.value,
  (field) => {
    if (field) void loadDictionaryRadioFacts(field);
  },
  { immediate: true },
);

watch(
  metadataFields,
  (fields) => {
    void Promise.all(fields.map((field) => loadDictionaryRadioFacts(field)));
  },
  { immediate: true },
);
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
    (!selectedActionEntry.value &&
      !selectedField.value &&
      !selectedQuickSearch.value &&
      !selectedGroup.value &&
      !selectedRelation.value)
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

const treeLayouts = computed<Array<'form' | 'detail'>>(() =>
  state.separateDetail.value && editorMode.value === 'fields' ? ['detail', 'form'] : ['form'],
);
const mergeLayoutOpen = ref(false);
const mergeKeep = ref<'form' | 'detail'>('form');
watch(
  () => props.moduleAlias,
  () => {
    mergeLayoutOpen.value = false;
    componentSession.reset();
    componentCatalog.value = undefined;
    componentCatalogLoading.value = false;
    paletteMode.value = 'fields';
  },
);
function confirmMergeLayout() {
  if (isMutating.value || !state.separateDetail.value) return;
  state.mergeLayout(mergeKeep.value);
  mergeLayoutOpen.value = false;
}

async function confirmSplitLayout() {
  if (isMutating.value || state.separateDetail.value) return;
  const sequence = workspaceLoadSequence;
  const confirmed = await confirmAction({
    title: '分别编排详情和表单',
    content: '将复制当前布局，详情和表单此后独立维护。保存并生效后应用，是否继续？',
  });
  if (!confirmed || sequence !== workspaceLoadSequence || isMutating.value || state.separateDetail.value)
    return;
  propertyDrawerOpen.value = false;
  state.splitLayout();
  state.previewMode.value = 'detail';
}

function layoutHandlers(layout: 'form' | 'detail') {
  const inLayout =
    <T extends unknown[]>(handler: (...args: T) => unknown) =>
    (...args: T) => {
      state.selectLayout(layout);
      handler(...args);
      if (state.separateDetail.value && state.selectedNode.value?.slot === 'form')
        state.previewMode.value = layout === 'detail' ? 'detail' : 'edit';
    };
  return {
    'node-action': inLayout(
      (action: Parameters<typeof handleNodeAction>[0] | 'split-layout' | 'merge-layout', key: string) => {
        if (isMutating.value) return;
        if (action === 'split-layout') {
          void confirmSplitLayout();
          return;
        }
        if (action === 'merge-layout') {
          mergeKeep.value = layout;
          mergeLayoutOpen.value = true;
          return;
        }
        handleNodeAction(action, key);
      },
    ),
    select: inLayout(selectUiTreeKey),
    'double-click': inLayout(handleUiTreeDoubleClick),
    'reorder-list-field': inLayout(reorderListField),
    'reorder-form-field': inLayout(reorderFormField),
    'move-form-field-to-group': inLayout(moveFormFieldToGroup),
    'move-group-field-to-form': inLayout(moveGroupFieldToForm),
    'reorder-group-field': inLayout(reorderGroupField),
    'move-group-field-to-group': inLayout(moveGroupFieldToGroup),
    'reorder-group': inLayout(reorderGroup),
    'reorder-relation-field': inLayout(reorderRelationField),
    'reorder-query-summary': inLayout(reorderQuerySummary),
    'source-drop': inLayout(handleCompositionSourceDrop),
    'action-drop': inLayout(handlePreviewActionDrop),
  };
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
            {{ publishedRevision ? '继续编辑' : '配置页面' }}
          </UiButton>
          <template v-else>
            <UiButton v-if="hasUnsavedChanges" :disabled="isMutating" @click="discardUnsavedChanges">
              放弃本次更改
            </UiButton>
            <UiButton
              type="primary"
              :loading="publishing"
              :disabled="
                isMutating ||
                !hasPendingChanges ||
                draftConflict ||
                componentNameInvalid ||
                childInvalid ||
                propertyIssues.length > 0 ||
                dictionaryRadioIssues.length > 0 ||
                actionIssues.length > 0 ||
                unavailableSources.length > 0 ||
                hasSummaryIssues ||
                summaryCatalogBlocksMutation ||
                (summarySources.length > 0 && !supportsQuerySummaries) ||
                Boolean(draftParseError)
              "
              @click="saveAndApply"
            >
              保存并生效
            </UiButton>
          </template>
        </div>
      </template>
    </ManagementPanelHeader>
    <div v-if="configuredMode !== compositionMode" class="page-composition-mode">
      <UiButton :disabled="isMutating" @click="applyConfiguredMode">采用概览中的呈现方式</UiButton>
    </div>
    <ManagementWorkspace class="page-composition-workspace__body" layout="composer" :explorer-count="2">
      <ManagementExplorerColumn collapsible :title="editorMode === 'fields' ? '字段来源' : paletteTitle">
        <div class="page-composition-palette">
          <RecordExplorerPanel
            v-if="editorMode === 'fields' && paletteMode === 'components'"
            title="组件库"
            :searchable="false"
            :refresh-disabled="isMutating || componentCatalogLoading"
            @refresh="loadComponentCatalog"
          >
            <template #title>
              <ManagementTabs
                v-model:active-key="paletteMode"
                :tabs="[
                  { key: 'fields', title: '元数据' },
                  { key: 'components', title: '组件库' },
                ]"
                :disabled="isMutating"
                label="字段来源"
                appearance="header"
              />
            </template>

            <div class="page-composition-component-list">
              <p class="page-composition-component-help">
                拖入组件新建数据项；同一数据在多处展示，请复用已有字段。
              </p>
              <UiTree
                v-if="componentCatalog"
                :nodes="componentNodes"
                :draggable="!isMutating"
                :drag-operations="['copy']"
                :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
                :drag-payload-of="
                  (node: UiTreeNode) =>
                    node.key === 'child' ? { kind: 'child' } : { kind: 'component', component: node.key }
                "
                :allow-drop="() => false"
                data-testid="page-composer-component-library"
              />
              <UiEmpty v-else :description="componentCatalogLoading ? '加载组件库' : '当前无法新增数据项'" />
            </div>
          </RecordExplorerPanel>
          <MetadataSourceTree
            v-else
            v-model:search-keyword="fieldKeyword"
            v-model:show-system-fields="showSystemFields"
            :title="editorMode === 'fields' ? '已有字段' : paletteTitle"
            :utility-placement="editorMode === 'fields' ? 'toolbar' : 'header'"
            :searchable="editorMode === 'fields'"
            :refresh-disabled="isMutating"
            :loading="loading && !relation"
            :unavailable="!relation"
            unavailable-description="页面编排仅面向已发布主元数据；当前模块暂无可编排主实体"
            v-model:expanded-keys="metadataExpandedKeys"
            :nodes="metadataTreeNodes"
            :load-children="loadReferenceChildren"
            :reload-key="metadataTreeReloadKey"
            :selected-key="selectedMetadataTreeKey"
            :draggable="!isMutating"
            :drag-payload-type="PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE"
            :drag-payload-of="metadataDragPayload"
            :can-drag="canDragMetadataNode"
            data-testid="page-composer-metadata-tree"
            @refresh="loadMetadataTree"
            @select="selectMetadataNode"
            @action="addMetadataNode"
          >
            <template v-if="editorMode === 'fields'" #title>
              <ManagementTabs
                v-model:active-key="paletteMode"
                :tabs="[
                  { key: 'fields', title: '元数据' },
                  { key: 'components', title: '组件库' },
                ]"
                :disabled="isMutating"
                label="字段来源"
                appearance="header"
              />
            </template>
            <UiEmpty v-if="editorMode === 'actions' && !moduleActions.length" description="暂无模块动作" />
            <UiEmpty v-if="editorMode === 'fields' && !visibleFields.length" description="暂无可编排字段" />
          </MetadataSourceTree>
        </div>
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
              v-for="layout in treeLayouts"
              :key="layout"
              :layout-title="state.separateDetail.value ? (layout === 'detail' ? '详情' : '表单') : undefined"
              :hide-list="state.separateDetail.value && layout === 'form'"
              :separate-detail="state.separateDetail.value"
              :skeleton="skeleton"
              :searchable-field-ids="
                allMetadataFields
                  .filter((field) => searchableFields.includes(field.fieldName))
                  .map((field) => field.id)
              "
              :quick-search-fields="
                quickSearchFields.map((fieldName) => ({
                  fieldName,
                  title: allMetadataFields.find((field) => field.fieldName === fieldName)?.title ?? fieldName,
                }))
              "
              :query-summaries="summarySources"
              :summaries-supported="supportsQuerySummaries"
              :summary-descriptions="summaryDescriptions"
              :summary-issues="summaryTreeIssues"
              :explorer-title="
                allMetadataFields.find((field) => field.fieldName === explorerTitleField)?.title ??
                explorerTitleField
              "
              :explorer-secondary="
                allMetadataFields.find((field) => field.fieldName === explorerSecondaryField)?.title ??
                explorerSecondaryField
              "
              :list-fields="state.listFields.value"
              :form-fields="state.layouts.value[layout].fields"
              :form-groups="state.layouts.value[layout].groups"
              :form-order="
                orderedFormItems(
                  state.layouts.value[layout].fields,
                  state.layouts.value[layout].groups,
                  state.layouts.value[layout].order,
                )
              "
              :form-relations="layout === 'form' ? state.formRelations.value : []"
              :selected-key="state.activeLayout.value === layout ? selectedUiTreeKey : undefined"
              :disabled="isMutating"
              :action-placements="actionPlacements"
              :module-actions="moduleActions"
              :editor-mode="editorMode"
              v-on="layoutHandlers(layout)"
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
        <div v-if="componentNameInvalid || childInvalid" role="alert" class="page-composition-source-error">
          请填写数据项和明细表名称（最多 128 字），明细表至少添加一个字段。
        </div>
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
          <span>页面配置已被其他会话更新，本地修改已保留。请加载最新配置后继续编辑。</span>
          <UiButton :disabled="isMutating" @click="reloadComposition">加载最新配置</UiButton>
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
            unavailableSources.length ||
            draftParseError ||
            dictionaryRadioIssues.length ||
            (hasCatalogDependentSummary && summaryCatalogError)
          "
          class="page-composition-source-error"
          role="alert"
        >
          {{
            draftParseError ??
            (dictionaryRadioIssues.length
              ? `字典 radio 配置需修正：${dictionaryRadioIssues.join('；')}。`
              : undefined) ??
            (hasCatalogDependentSummary ? summaryCatalogError : undefined) ??
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
        :open="mergeLayoutOpen"
        title="恢复共用布局"
        render-mode="inline"
        width="compact"
        @close="mergeLayoutOpen = false"
      >
        <div class="component-property-drawer">
          <fieldset class="page-composition-layout-choice">
            <legend>共用哪套布局</legend>
            <UiRadioGroup
              v-model:value="mergeKeep"
              :disabled="isMutating"
              :options="[
                { value: 'form', label: '表单布局' },
                { value: 'detail', label: '详情布局' },
              ]"
            />
          </fieldset>
          <p>
            {{
              mergeKeep === 'form'
                ? '详情将采用当前表单的字段、分组和顺序，原详情编排将被覆盖。'
                : '表单将采用当前详情的字段、分组和顺序，原表单编排将被覆盖。'
            }}
          </p>
        </div>
        <template #operation>
          <UiButton :disabled="isMutating" @click="mergeLayoutOpen = false">取消</UiButton>
          <UiButton type="primary" :disabled="isMutating" @click="confirmMergeLayout">恢复共用</UiButton>
        </template>
      </RecordDetailDrawer>
      <RecordDetailDrawer
        :open="propertyDrawerOpen"
        render-mode="inline"
        :title="propertyDrawerTitle"
        width="compact"
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
          <h3>数据项</h3>
          <p v-if="!selectedPendingComponent">
            {{ selectedField.title }} · {{ selectedField.required ? '必填' : '选填' }}
          </p>
          <p v-if="selectedFieldUsage.length">已用于：{{ selectedFieldUsage.join('、') }}</p>
          <label v-if="selectedPendingComponent">
            <span>数据项名称</span>
            <UiInput
              :value="selectedPendingComponent.title"
              :disabled="isMutating"
              :maxlength="128"
              placeholder="例如：供应商名称"
              @update:value="updateComponentTitle"
            />
          </label>
          <label v-if="selectedPendingComponent" class="component-property-drawer__switch">
            <span>必填</span>
            <UiSwitch
              :checked="selectedPendingComponent.required ?? false"
              :disabled="isMutating"
              @update:checked="updateComponentRequired"
            />
          </label>
          <small v-if="selectedPendingComponent">名称和必填规则适用于所有使用位置。</small>
          <div v-if="duplicateComponentFields.length" role="status">
            <p>
              已有同名数据项“{{
                selectedPendingComponent?.title.trim()
              }}”。若要展示同一份数据，请移除此新增组件并复用已有字段。
            </p>
            <UiButton
              :disabled="isMutating"
              @click="
                paletteMode = 'fields';
                fieldKeyword = selectedPendingComponent?.title.trim() ?? '';
                propertyDrawerOpen = false;
              "
              >查找同名字段</UiButton
            >
          </div>
          <h3>当前展示位置</h3>
          <small>以下设置仅影响当前区域，不修改数据项。</small>
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
            <label v-if="selectedDirectReferenceFormField">
              <span>引用选择形式</span>
              <UiSelect
                :value="referencePickerPresentationValue"
                :options="referencePickerPresentationOptions"
                :disabled="isMutating"
                :allow-clear="false"
                @update:value="updateReferencePickerPresentation"
              />
            </label>
            <label v-if="selectedDirectDictionaryFormField">
              <span>字典展示形式</span>
              <UiSelect
                :value="dictionaryPresentationValue"
                :options="dictionaryPresentationOptions"
                :disabled="isMutating"
                :allow-clear="false"
                @update:value="updateDictionaryPresentation"
              />
              <small v-if="selectedDirectDictionaryFormField.optionSelectionMode === 'SINGLE'">
                {{
                  selectedDictionaryRadioIssue ??
                  `radio 可用：不超过 ${dictionaryRadioMaxOptions} 个启用候选且没有层级。`
                }}
              </small>
            </label>
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
        <div v-else-if="selectedRelation" class="component-property-drawer">
          <label
            ><span>明细表名称</span
            ><UiInput
              :value="selectedRelation.title"
              :maxlength="128"
              :disabled="isMutating"
              @update:value="selectedRelation.title = $event"
          /></label>
          <p v-if="pendingChildren.some((child) => child.key === selectedRelation?.id)">
            将基础组件拖入明细表，添加明细字段。
          </p>
          <p>明细随主表一起编辑和保存。</p>
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
        width="narrow"
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
.page-composition-palette,
.page-composition-component-list {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}
.page-composition-component-list {
  gap: 8px;
}
.page-composition-palette > :deep(.record-explorer-panel) {
  flex: 1 1 auto;
}
.page-composition-component-list > .page-composition-component-help {
  flex: 0 0 auto;
  color: var(--muyun-text-muted);
  margin: 0 0 12px;
  font-size: 13px;
}
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

.page-composition-structure {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 0;
  overflow: hidden;
}
.page-composition-structure :deep(.ant-tree) {
  flex: 0 0 auto;
  min-height: 0;
  overflow: visible;
}
/* Keep node names legible beside stable inline action slots in the compact composer. */
.page-composition-structure :deep(.ant-tree-indent-unit) {
  width: 16px;
}
.page-composition-structure :deep(.ui-record-explorer-item-title) {
  flex-shrink: 0;
}
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
.page-composition-layout-choice {
  margin: 0;
  padding: 0;
  border: 0;
}
.page-composition-layout-choice legend {
  margin-bottom: 8px;
  color: var(--muyun-text-muted);
  font-size: 13px;
}
.component-property-drawer {
  display: grid;
  gap: 16px;
}
.component-property-drawer h3 {
  margin: 0;
  font-size: 14px;
}
.component-property-drawer small {
  color: var(--muyun-text-muted);
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
